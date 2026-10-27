package com.vodianoy.pgvc.vcs;

import com.vodianoy.pgvc.common.ApiException;
import com.vodianoy.pgvc.environment.EnvironmentManagerService;
import com.vodianoy.pgvc.storage.PgVcStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Deletes commits that are no longer used: commits left behind by a rollback, abandoned branches.
 * <p>
 * A commit can be deleted only if it is not part of the current history (HEAD and its ancestors) of any environment;
 * the database schema is never touched, only the version history. The commits are deleted from every environment
 * whose _pgvc.commits contains them. All environments must be reachable, otherwise it is unknown whether one of them
 * still uses the commits.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommitDeletionService {

    private final VersionControlService versionControl;
    private final EnvironmentManagerService environments;
    private final PgVcStorageService storage;
    private final CommitHistory history;

    public DeletionPlan plan(String hash, DeletionMode mode) {
        return plan(versionControl.getGlobalGraph(), hash, mode == null ? DeletionMode.COMMIT : mode);
    }

    static DeletionPlan plan(GlobalGraph graph, String hash, DeletionMode mode) {
        Map<String, GlobalGraph.Commit> byHash = graph.commits().stream()
                .collect(Collectors.toMap(GlobalGraph.Commit::hash, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        if (hash == null || !byHash.containsKey(hash)) {
            throw ApiException.notFound("delete.notFound", shortHash(hash));
        }
        Map<String, List<String>> children = new HashMap<>();
        for (GlobalGraph.Commit c : byHash.values()) {
            if (c.parentHash() != null) {
                children.computeIfAbsent(c.parentHash(), k -> new ArrayList<>()).add(c.hash());
            }
        }

        // the live history of every environment: HEAD and all its ancestors
        Map<String, Set<String>> live = new LinkedHashMap<>();
        Set<String> anyLive = new HashSet<>();
        for (GlobalGraph.EnvironmentHead env : graph.environments()) {
            Set<String> ancestors = new HashSet<>();
            String current = env.head();
            while (current != null && ancestors.add(current)) {
                GlobalGraph.Commit c = byHash.get(current);
                current = c == null ? null : c.parentHash();
            }
            live.put(env.name(), ancestors);
            anyLive.addAll(ancestors);
        }

        // the commit and all its descendants
        Set<String> doomed = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(List.of(hash));
        while (!queue.isEmpty()) {
            String h = queue.poll();
            if (doomed.add(h)) {
                queue.addAll(children.getOrDefault(h, List.of()));
            }
        }
        if (mode == DeletionMode.BRANCH) {
            // walk down the abandoned branch while the ancestor is not used and has no other children
            String parent = byHash.get(hash).parentHash();
            while (parent != null && byHash.containsKey(parent) && !anyLive.contains(parent)
                    && doomed.containsAll(children.getOrDefault(parent, List.of()))) {
                doomed.add(parent);
                parent = byHash.get(parent).parentHash();
            }
        }

        List<String> blockedBy = live.entrySet().stream()
                .filter(e -> !Collections.disjoint(e.getValue(), doomed))
                .map(Map.Entry::getKey).toList();
        List<String> offline = graph.environments().stream()
                .filter(e -> !e.reachable()).map(GlobalGraph.EnvironmentHead::name).toList();
        List<GlobalGraph.Commit> commits = doomed.stream().map(byHash::get)
                .sorted(Comparator.comparing(GlobalGraph.Commit::createdAt,
                        Comparator.nullsLast(Comparator.<OffsetDateTime>reverseOrder())))
                .toList();
        List<String> envs = commits.stream().flatMap(c -> c.environments().stream()).distinct().toList();
        return new DeletionPlan(hash, mode, commits, envs, blockedBy, offline, blockedBy.isEmpty() && offline.isEmpty());
    }

    /**
     * Deletes the commits of {@link #plan}. Every environment is changed in its own transaction under the history
     * lock and re-checked there: if its HEAD moved onto one of the commits meanwhile, nothing more is deleted.
     */
    public DeletionResult delete(String hash, DeletionMode mode) {
        DeletionMode m = mode == null ? DeletionMode.COMMIT : mode;
        DeletionPlan plan = plan(hash, m);
        if (!plan.offlineEnvironments().isEmpty()) {
            throw ApiException.conflict("delete.offline", String.join(", ", plan.offlineEnvironments()));
        }
        if (!plan.blockedBy().isEmpty()) {
            throw ApiException.conflict("delete.reachable", shortHash(hash), String.join(", ", plan.blockedBy()));
        }
        Set<String> doomed = plan.commits().stream().map(GlobalGraph.Commit::hash)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, Integer> perEnvironment = new LinkedHashMap<>();
        for (String envId : plan.environments()) {
            Integer deleted = environments.getTransactionTemplate(envId).execute(tx -> {
                storage.lockHistory(envId);
                String head = storage.getHead(envId).orElse(null);
                if (head != null && !Collections.disjoint(history.ancestors(envId, head), doomed)) {
                    throw ApiException.conflict("delete.changed", environments.getEnvironment(envId).getName());
                }
                return storage.deleteCommits(envId, doomed);
            });
            perEnvironment.put(envId, deleted == null ? 0 : deleted);
        }
        log.info("Deleted {} commit(s) ({} {}) from {}", doomed.size(), m, shortHash(hash), perEnvironment);
        return new DeletionResult(hash, m, List.copyOf(doomed), perEnvironment);
    }

    private static String shortHash(String hash) {
        return hash == null ? "<none>" : hash.substring(0, Math.min(10, hash.length()));
    }
}
