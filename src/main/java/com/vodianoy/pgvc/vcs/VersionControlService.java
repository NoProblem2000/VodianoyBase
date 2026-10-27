package com.vodianoy.pgvc.vcs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vodianoy.pgvc.common.ApiException;
import com.vodianoy.pgvc.diff.ChangeItem;
import com.vodianoy.pgvc.diff.SchemaDiffEngine;
import com.vodianoy.pgvc.diff.SchemaDiffResult;
import com.vodianoy.pgvc.environment.Environment;
import com.vodianoy.pgvc.environment.EnvironmentManagerService;
import com.vodianoy.pgvc.introspection.PostgresSchemaIntrospector;
import com.vodianoy.pgvc.model.schema.DatabaseSnapshot;
import com.vodianoy.pgvc.storage.AuditLogEntry;
import com.vodianoy.pgvc.storage.CommitNode;
import com.vodianoy.pgvc.storage.CommitRecord;
import com.vodianoy.pgvc.storage.PgVcStatus;
import com.vodianoy.pgvc.storage.PgVcStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Version-control workflows of one environment:
 * <ul>
 *   <li>Mode A: {@link #getWorkingTreeStatus} shows what changed since HEAD, {@link #commitWorkingTree} records it;</li>
 *   <li>Mode B: {@link #executeAndCommitSql} runs a migration and records it in the same transaction;</li>
 *   <li>{@link #rollbackToCommit} reverts the database to an earlier commit.</li>
 * </ul>
 * Every write runs in one transaction of the environment and holds an advisory lock on the history.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VersionControlService {

    /** Transaction control inside user SQL would break the single-transaction guarantee. */
    private static final Pattern TRANSACTION_CONTROL = Pattern.compile(
            "(?im)^\\s*(BEGIN|COMMIT|ROLLBACK|END|ABORT|START\\s+TRANSACTION)\\s*(WORK|TRANSACTION)?\\s*;");
    private static final int MAX_AUTHOR_LENGTH = 100;

    private final EnvironmentManagerService environments;
    private final PgVcStorageService storage;
    private final PostgresSchemaIntrospector introspector;
    private final SchemaDiffEngine diffEngine;
    private final ObjectMapper objectMapper;
    private final CommitHistory history;

    // ------------------------------------------------------------------ Mode A

    /** HEAD snapshot vs live schema, plus the DDL captured since the last commit. */
    public WorkingTreeStatus getWorkingTreeStatus(String envId) {
        Optional<String> head = storage.getHead(envId);
        DatabaseSnapshot base = headSnapshot(envId, head.orElse(null));
        DatabaseSnapshot live = introspector.introspect(envId);
        SchemaDiffResult diff = diffEngine.diff(base, live);
        return new WorkingTreeStatus(envId, head.orElse(null), diff, storage.readAuditLog(envId));
    }

    /**
     * Records the current live schema as a new commit on top of HEAD. The UP / DOWN SQL from the request are stored
     * as they are (the user may have edited them); blank values are replaced with the generated scripts. The changes
     * are already in the database, so nothing is executed.
     */
    public CommitResult commitWorkingTree(String envId, CommitRequest request) {
        String message = requireMessage(request.message());
        String author = author(request.author(), envId);
        return environments.getTransactionTemplate(envId).execute(tx -> {
            storage.lockHistory(envId);
            String head = storage.getHead(envId).orElse(null);
            if (request.expectedHead() != null && !request.expectedHead().isBlank()
                    && !request.expectedHead().equals(head)) {
                throw ApiException.conflict("commit.headMoved", envId, shortHash(head));
            }
            DatabaseSnapshot live = introspector.introspect(envId);
            SchemaDiffResult diff = diffEngine.diff(headSnapshot(envId, head), live);
            if (!diff.isHasChanges() && isBlank(request.upSql())) {
                throw ApiException.badRequest("commit.nothing");
            }
            List<AuditLogEntry> audit = storage.readAuditLog(envId);
            CommitRecord commit = newCommit(head, author, message,
                    isBlank(request.upSql()) ? diff.getUpSql() : request.upSql(),
                    isBlank(request.downSql()) ? diff.getDownSql() : request.downSql(),
                    live, audit);
            storage.insertCommit(envId, commit);
            storage.setHead(envId, commit.getHash());
            if (!audit.isEmpty()) {
                // entries logged by other sessions after we read the log stay for the next commit
                storage.clearAuditLog(envId, audit.get(audit.size() - 1).id());
            }
            log.info("Committed {} on {}: {}", shortHash(commit.getHash()), envId, message);
            return new CommitResult(commit.toNode(), diff.getChanges());
        });
    }

    // ------------------------------------------------------------------ Mode B

    /**
     * Executes the migration, reads the new schema inside the same transaction, generates DOWN SQL when none was
     * given, stores the commit and commits. Any error rolls everything back.
     */
    public CommitResult executeAndCommitSql(String envId, ExecuteMigrationRequest request) {
        String message = requireMessage(request.message());
        String author = author(request.author(), envId);
        if (isBlank(request.upSql())) {
            throw ApiException.badRequest("migration.upSqlEmpty");
        }
        if (TRANSACTION_CONTROL.matcher(request.upSql()).find()) {
            throw ApiException.badRequest("migration.transactionControl");
        }
        return environments.getTransactionTemplate(envId).execute(tx -> {
            storage.lockHistory(envId);
            String head = storage.getHead(envId).orElse(null);
            DatabaseSnapshot before = introspector.introspect(envId);
            SchemaDiffResult drift = diffEngine.diff(headSnapshot(envId, head), before);
            if (drift.isHasChanges() && !request.allowDirty()) {
                throw ApiException.conflict("migration.dirty", drift.getChanges().size(), summary(drift.getChanges()));
            }

            environments.getJdbcTemplate(envId).execute(request.upSql());

            DatabaseSnapshot after = introspector.introspect(envId);
            SchemaDiffResult change = diffEngine.diff(before, after);
            String downSql = isBlank(request.downSql()) ? change.getDownSql() : request.downSql();
            List<AuditLogEntry> audit = storage.readAuditLog(envId);
            CommitRecord commit = newCommit(head, author, message, request.upSql(), downSql, after, audit);
            storage.insertCommit(envId, commit);
            storage.setHead(envId, commit.getHash());
            storage.clearAuditLog(envId);
            log.info("Executed and committed {} on {}: {}", shortHash(commit.getHash()), envId, message);
            return new CommitResult(commit.toNode(), change.getChanges());
        });
    }

    // ------------------------------------------------------------------ rollback

    public RollbackResult rollbackToCommit(String envId, String targetHash, String customRollbackSql) {
        return rollbackToCommit(envId, new RollbackRequest(targetHash, customRollbackSql, false, false));
    }

    /**
     * Executes the down_sql of every commit after {@code targetHash} (newest first) - or the user's
     * {@code customRollbackSql} - in one transaction, checks that the schema now equals the snapshot of the target
     * commit, moves HEAD to the target and clears the audit log. Any error or mismatch rolls everything back.
     */
    public RollbackResult rollbackToCommit(String envId, RollbackRequest request) {
        if (isBlank(request.targetHash())) {
            throw ApiException.badRequest("rollback.targetRequired");
        }
        if (!isBlank(request.customRollbackSql()) && TRANSACTION_CONTROL.matcher(request.customRollbackSql()).find()) {
            throw ApiException.badRequest("rollback.transactionControl");
        }
        return environments.getTransactionTemplate(envId).execute(tx -> {
            storage.lockHistory(envId);
            String head = storage.getHead(envId).orElseThrow(() ->
                    ApiException.conflict("rollback.noCommits", envId));
            List<CommitRecord> chain = chainToAncestor(envId, head, request.targetHash());
            CommitRecord target = storage.requireCommit(envId, request.targetHash());

            if (!request.allowDirty()) {
                SchemaDiffResult drift = diffEngine.diff(headSnapshot(envId, head), introspector.introspect(envId));
                if (drift.isHasChanges()) {
                    throw ApiException.conflict("rollback.dirty", summary(drift.getChanges()));
                }
            }

            String sql = isBlank(request.customRollbackSql()) ? rollbackSql(chain) : request.customRollbackSql();
            environments.getJdbcTemplate(envId).execute(sql);

            boolean verify = !request.skipVerification();
            if (verify) {
                SchemaDiffResult rest = diffEngine.diff(parseSnapshot(target.getSchemaSnapshotJson()),
                        introspector.introspect(envId));
                if (rest.isHasChanges()) {
                    throw ApiException.conflict("rollback.verifyFailed", shortHash(target.getHash()), summary(rest.getChanges()));
                }
            }
            storage.setHead(envId, target.getHash());
            storage.clearAuditLog(envId);
            log.info("Rolled back {} from {} to {}", envId, shortHash(head), shortHash(target.getHash()));
            return new RollbackResult(head, target.getHash(), chain.stream().map(CommitRecord::toNode).toList(), sql, verify);
        });
    }

    /** The down_sql chain a rollback to {@code targetHash} would execute (for review / editing in the UI). */
    public RollbackPlan getRollbackPlan(String envId, String targetHash) {
        String head = storage.getHead(envId).orElseThrow(() ->
                ApiException.conflict("rollback.noCommits", envId));
        List<CommitRecord> chain = chainToAncestor(envId, head, targetHash);
        return new RollbackPlan(head, targetHash, chain.stream().map(CommitRecord::toNode).toList(), rollbackSql(chain));
    }

    // ------------------------------------------------------------------ multi-environment sync

    /** How the history and the schema of {@code targetEnvId} relate to {@code sourceEnvId}. */
    public EnvironmentComparison compareEnvironments(String sourceEnvId, String targetEnvId) {
        requireDifferent(sourceEnvId, targetEnvId);
        String sourceHead = storage.getHead(sourceEnvId).orElse(null);
        PgVcStatus target = requireReachable(targetEnvId);
        String targetHead = target.initialized() ? target.head() : null;

        HistoryRelation relation;
        String commonAncestor = null;
        List<CommitRecord> toPush = List.of();
        if (!target.initialized()) {
            relation = HistoryRelation.TARGET_NOT_INITIALIZED;
            toPush = sourceHead == null ? List.of() : history.range(sourceEnvId, null, sourceHead);
        } else if (Objects.equals(sourceHead, targetHead)) {
            relation = HistoryRelation.SAME;
            commonAncestor = sourceHead;
        } else if (targetHead == null) {
            relation = HistoryRelation.TARGET_EMPTY;
            toPush = history.range(sourceEnvId, null, sourceHead);
        } else {
            Set<String> sourceAncestors = sourceHead == null ? Set.of() : history.ancestors(sourceEnvId, sourceHead);
            if (sourceAncestors.contains(targetHead)) {
                relation = HistoryRelation.TARGET_BEHIND;
                commonAncestor = targetHead;
                toPush = history.range(sourceEnvId, targetHead, sourceHead);
            } else {
                Set<String> targetAncestors = history.ancestors(targetEnvId, targetHead);
                if (sourceHead != null && targetAncestors.contains(sourceHead)) {
                    relation = HistoryRelation.TARGET_AHEAD;
                    commonAncestor = sourceHead;
                } else {
                    relation = HistoryRelation.DIVERGED;
                    commonAncestor = targetAncestors.stream().filter(sourceAncestors::contains).findFirst().orElse(null);
                }
            }
        }

        DatabaseSnapshot targetLive = introspector.introspect(targetEnvId);
        boolean dirty = target.initialized()
                ? diffEngine.diff(headSnapshot(targetEnvId, targetHead), targetLive).isHasChanges()
                : !targetLive.isEmpty();
        SchemaDiffResult schemaDiff = diffEngine.diff(targetLive, headSnapshot(sourceEnvId, sourceHead));
        boolean unconnected = relation == HistoryRelation.DIVERGED
                || ((relation == HistoryRelation.TARGET_EMPTY || relation == HistoryRelation.TARGET_NOT_INITIALIZED)
                && !targetLive.isEmpty());
        CommitNode matching = unconnected && sourceHead != null
                ? findMatchingCommit(sourceEnvId, sourceHead, targetLive).map(CommitRecord::toNode).orElse(null)
                : null;
        return new EnvironmentComparison(sourceEnvId, targetEnvId, sourceHead, targetHead, relation, commonAncestor,
                toPush.stream().map(CommitRecord::toNode).toList(), dirty, schemaDiff, matching);
    }

    /** How many commits of the source history (newest first) are compared with the target schema. */
    private static final int MATCH_SEARCH_LIMIT = 200;

    /** Newest commit in the history of {@code head} whose schema snapshot equals {@code schema}. */
    private Optional<CommitRecord> findMatchingCommit(String envId, String head, DatabaseSnapshot schema) {
        List<CommitRecord> chain = history.range(envId, null, head);
        for (int i = chain.size() - 1, n = 0; i >= 0 && n < MATCH_SEARCH_LIMIT; i--, n++) {
            CommitRecord c = chain.get(i);
            if (!diffEngine.diff(parseSnapshot(c.getSchemaSnapshotJson()), schema).isHasChanges()) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }

    /**
     * Baseline: makes a target environment whose schema already exists part of the source history without executing
     * any migration. In one transaction of the target: checks that its live schema equals the snapshot of the chosen
     * source commit, writes the source commits up to that commit (same hashes), points HEAD at it, deletes the target's
     * own previous commits (unless asked to keep them) and clears its DDL audit log. Initializes _pgvc first if needed.
     * Afterwards the target is "behind" the source and later commits can be pushed normally.
     */
    public LinkResult linkEnvironmentToHistory(LinkRequest request) {
        String sourceEnvId = request.sourceEnvironmentId();
        String targetEnvId = request.targetEnvironmentId();
        requireDifferent(sourceEnvId, targetEnvId);
        String sourceHead = storage.getHead(sourceEnvId).orElseThrow(() ->
                ApiException.badRequest("link.noCommits", sourceEnvId));
        String requested = isBlank(request.commitHash()) ? null : request.commitHash().strip();
        if (requested != null) {
            storage.requireCommit(sourceEnvId, requested);
            if (!history.ancestors(sourceEnvId, sourceHead).contains(requested)) {
                throw ApiException.badRequest("link.notInHistory", shortHash(requested), sourceEnvId, shortHash(sourceHead));
            }
        }
        if (!requireReachable(targetEnvId).initialized()) {
            storage.initializeEnvironment(targetEnvId);
        }

        return environments.getTransactionTemplate(targetEnvId).execute(tx -> {
            storage.lockHistory(targetEnvId);
            String previousHead = storage.getHead(targetEnvId).orElse(null);
            DatabaseSnapshot live = introspector.introspect(targetEnvId);
            CommitRecord commit = requested != null
                    ? storage.requireCommit(sourceEnvId, requested)
                    : findMatchingCommit(sourceEnvId, sourceHead, live).orElseThrow(() -> ApiException.conflict("link.noMatch", targetEnvId, sourceEnvId));
            if (commit.getHash().equals(previousHead)) {
                throw ApiException.badRequest("sync.alreadyAt", targetEnvId, shortHash(previousHead));
            }

            SchemaDiffResult diff = diffEngine.diff(parseSnapshot(commit.getSchemaSnapshotJson()), live);
            if (diff.isHasChanges() && !request.allowSchemaDifferences()) {
                throw ApiException.conflict("link.schemaDiffers", targetEnvId, shortHash(commit.getHash()), commit.getMessage(), summary(diff.getChanges()));
            }

            List<CommitRecord> linked = history.range(sourceEnvId, null, commit.getHash());
            for (CommitRecord c : linked) {
                storage.insertCommit(targetEnvId, c);
            }
            storage.setHead(targetEnvId, commit.getHash());
            int discarded = request.keepTargetCommits() ? 0
                    : storage.deleteCommitsExcept(targetEnvId, linked.stream().map(CommitRecord::getHash).toList());
            storage.clearAuditLog(targetEnvId);
            log.info("Linked {} to the history of {} at {} (previous HEAD {}, {} own commit(s) deleted)", targetEnvId,
                    sourceEnvId, shortHash(commit.getHash()), shortHash(previousHead), discarded);
            return new LinkResult(sourceEnvId, targetEnvId, previousHead, commit.getHash(),
                    linked.stream().map(CommitRecord::toNode).toList(), discarded,
                    diff.isHasChanges() ? diff.getChanges() : List.of());
        });
    }

    public PushResult pushCommitsToEnvironment(String sourceEnvId, String targetEnvId, String targetCommitHash) {
        return pushCommitsToEnvironment(new PushRequest(sourceEnvId, targetEnvId, targetCommitHash, false, false));
    }

    /**
     * Applies the commits of the source that the target is missing, in one transaction of the target: up_sql of
     * every commit, the commit rows (same hashes and snapshots) and HEAD; then checks that the target schema equals
     * the snapshot of the last commit. Initializes _pgvc in the target first if needed.
     */
    public PushResult pushCommitsToEnvironment(PushRequest request) {
        String sourceEnvId = request.sourceEnvironmentId();
        String targetEnvId = request.targetEnvironmentId();
        requireDifferent(sourceEnvId, targetEnvId);
        String tip = isBlank(request.targetCommitHash())
                ? storage.getHead(sourceEnvId).orElseThrow(() ->
                        ApiException.badRequest("push.noCommits", sourceEnvId))
                : request.targetCommitHash().strip();
        storage.requireCommit(sourceEnvId, tip);
        if (!requireReachable(targetEnvId).initialized()) {
            storage.initializeEnvironment(targetEnvId);
        }

        return environments.getTransactionTemplate(targetEnvId).execute(tx -> {
            storage.lockHistory(targetEnvId);
            String targetHead = storage.getHead(targetEnvId).orElse(null);
            if (tip.equals(targetHead)) {
                throw ApiException.badRequest("sync.alreadyAt", targetEnvId, shortHash(tip));
            }
            if (targetHead != null && !history.ancestors(sourceEnvId, tip).contains(targetHead)) {
                throw ApiException.conflict("push.notAncestor", shortHash(targetHead), targetEnvId, shortHash(tip), sourceEnvId);
            }
            List<CommitRecord> commits = history.range(sourceEnvId, targetHead, tip);

            if (!request.allowDirty()) {
                DatabaseSnapshot live = introspector.introspect(targetEnvId);
                if (targetHead == null && !live.isEmpty()) {
                    throw ApiException.conflict("push.untrackedTarget", targetEnvId);
                }
                if (targetHead != null) {
                    SchemaDiffResult drift = diffEngine.diff(headSnapshot(targetEnvId, targetHead), live);
                    if (drift.isHasChanges()) {
                        throw ApiException.conflict("push.dirty", targetEnvId, summary(drift.getChanges()));
                    }
                }
            }

            for (CommitRecord c : commits) {
                try {
                    environments.getJdbcTemplate(targetEnvId).execute(c.getUpSql());
                } catch (DataAccessException e) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "push.commitFailed", shortHash(c.getHash()),
                            c.getMessage(), targetEnvId, NestedExceptionUtils.getMostSpecificCause(e).getMessage());
                }
                storage.insertCommit(targetEnvId, c);
            }
            storage.setHead(targetEnvId, tip);
            storage.clearAuditLog(targetEnvId);

            boolean verify = !request.skipVerification();
            if (verify) {
                CommitRecord last = commits.get(commits.size() - 1);
                SchemaDiffResult rest = diffEngine.diff(parseSnapshot(last.getSchemaSnapshotJson()),
                        introspector.introspect(targetEnvId));
                if (rest.isHasChanges()) {
                    throw ApiException.conflict("push.verifyFailed", targetEnvId, shortHash(tip), summary(rest.getChanges()));
                }
            }
            log.info("Pushed {} commit(s) {} -> {}: HEAD {} -> {}", commits.size(), sourceEnvId, targetEnvId,
                    shortHash(targetHead), shortHash(tip));
            return new PushResult(sourceEnvId, targetEnvId, targetHead, tip,
                    commits.stream().map(CommitRecord::toNode).toList(), verify);
        });
    }

    /** Commits of all environments merged into one graph, with the HEAD of every environment. */
    public GlobalGraph getGlobalGraph() {
        List<Environment> envs = environments.listEnvironments();
        String activeId = environments.getActiveEnvironment().map(Environment::getId).orElse(null);
        record EnvState(GlobalGraph.EnvironmentHead head, List<CommitNode> commits) {
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(envs.size(), 8)));
        try {
            List<CompletableFuture<EnvState>> futures = envs.stream().map(env -> CompletableFuture.supplyAsync(() -> {
                boolean active = env.getId().equals(activeId);
                try {
                    PgVcStatus st = storage.getStatus(env.getId());
                    List<CommitNode> commits = st.reachable() && st.initialized()
                            ? storage.getCommitGraph(env.getId()).commits() : List.of();
                    return new EnvState(new GlobalGraph.EnvironmentHead(env.getId(), env.getName(), env.getColorBadge(),
                            active, st.reachable(), st.initialized(), st.head(), st.error()), commits);
                } catch (RuntimeException e) {
                    return new EnvState(new GlobalGraph.EnvironmentHead(env.getId(), env.getName(), env.getColorBadge(),
                            active, false, false, null, NestedExceptionUtils.getMostSpecificCause(e).getMessage()), List.of());
                }
            }, pool)).toList();

            Map<String, CommitNode> byHash = new LinkedHashMap<>();
            Map<String, List<String>> presence = new HashMap<>();
            List<GlobalGraph.EnvironmentHead> heads = new ArrayList<>();
            for (CompletableFuture<EnvState> f : futures) {
                EnvState s = f.join();
                heads.add(s.head());
                for (CommitNode c : s.commits()) {
                    byHash.putIfAbsent(c.hash(), c);
                    presence.computeIfAbsent(c.hash(), k -> new ArrayList<>()).add(s.head().id());
                }
            }
            List<GlobalGraph.Commit> commits = byHash.values().stream()
                    .sorted(Comparator.comparing(CommitNode::createdAt, Comparator.nullsLast(Comparator.<OffsetDateTime>reverseOrder())))
                    .map(c -> new GlobalGraph.Commit(c.hash(), c.parentHash(), c.author(), c.message(), c.createdAt(),
                            presence.getOrDefault(c.hash(), List.of())))
                    .toList();
            return new GlobalGraph(commits, heads);
        } finally {
            pool.shutdown();
        }
    }

    private PgVcStatus requireReachable(String envId) {
        PgVcStatus status = storage.getStatus(envId);
        if (!status.reachable()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "env.unreachable", envId, status.error());
        }
        return status;
    }

    private static void requireDifferent(String sourceEnvId, String targetEnvId) {
        if (sourceEnvId == null || targetEnvId == null) {
            throw ApiException.badRequest("sync.envsRequired");
        }
        if (sourceEnvId.equals(targetEnvId)) {
            throw ApiException.badRequest("sync.envsSame");
        }
    }

    // ------------------------------------------------------------------ commits

    /** Schema changes a commit introduced: snapshot of its parent vs its own snapshot. */
    public SchemaDiffResult getCommitChanges(String envId, String hash) {
        CommitRecord c = storage.requireCommit(envId, hash);
        DatabaseSnapshot before = c.getParentHash() == null ? DatabaseSnapshot.builder().build()
                : storage.findCommit(envId, c.getParentHash())
                        .map(p -> parseSnapshot(p.getSchemaSnapshotJson()))
                        .orElseGet(() -> DatabaseSnapshot.builder().build());
        return diffEngine.diff(before, parseSnapshot(c.getSchemaSnapshotJson()));
    }

    public CommitDetails getCommit(String envId, String hash) {
        CommitRecord c = storage.requireCommit(envId, hash);
        return new CommitDetails(c.getHash(), c.getParentHash(), c.getAuthor(), c.getMessage(), c.getCreatedAt(),
                c.getUpSql(), c.getDownSql(), parseSnapshot(c.getSchemaSnapshotJson()), parseJson(c.getDdlLogHistoryJson()));
    }

    // ------------------------------------------------------------------ helpers

    /** Commits from HEAD (inclusive) down to the target (exclusive), newest first. */
    private List<CommitRecord> chainToAncestor(String envId, String head, String targetHash) {
        if (targetHash == null || targetHash.isBlank()) {
            throw ApiException.badRequest("rollback.targetRequired");
        }
        if (targetHash.equals(head)) {
            throw ApiException.badRequest("rollback.alreadyAt", shortHash(targetHash));
        }
        storage.requireCommit(envId, targetHash);
        List<CommitRecord> chain;
        try {
            chain = new ArrayList<>(history.range(envId, targetHash, head));
        } catch (ApiException e) {
            throw ApiException.conflict("rollback.notAncestor", shortHash(targetHash), shortHash(head));
        }
        Collections.reverse(chain);
        return chain;
    }

    private static String rollbackSql(List<CommitRecord> chainNewestFirst) {
        StringBuilder sb = new StringBuilder();
        for (CommitRecord c : chainNewestFirst) {
            sb.append("-- >>> revert ").append(shortHash(c.getHash())).append(": ")
                    .append(c.getMessage().replaceAll("[\\r\\n]+", " ")).append('\n');
            sb.append(c.getDownSql().strip()).append("\n\n");
        }
        return sb.toString();
    }

    private DatabaseSnapshot headSnapshot(String envId, String head) {
        if (head == null) {
            return DatabaseSnapshot.builder().build();
        }
        return parseSnapshot(storage.requireCommit(envId, head).getSchemaSnapshotJson());
    }

    private CommitRecord newCommit(String parent, String author, String message, String upSql, String downSql,
                                   DatabaseSnapshot snapshot, List<AuditLogEntry> audit) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        String snapshotJson = toJson(snapshot);
        String auditJson = audit.isEmpty() ? null : toJson(audit);
        String hash = CommitHasher.hash(parent, author, message, now.toString(), upSql, downSql, snapshotJson);
        return CommitRecord.builder().hash(hash).parentHash(parent).author(author).message(message).createdAt(now)
                .upSql(upSql).downSql(downSql).schemaSnapshotJson(snapshotJson).ddlLogHistoryJson(auditJson).build();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize " + value.getClass().getSimpleName(), e);
        }
    }

    private DatabaseSnapshot parseSnapshot(String json) {
        try {
            return objectMapper.readValue(json, DatabaseSnapshot.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored schema snapshot cannot be read: " + e.getOriginalMessage(), e);
        }
    }

    private JsonNode parseJson(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored JSON cannot be read: " + e.getOriginalMessage(), e);
        }
    }

    private String author(String requested, String envId) {
        String a = isBlank(requested) ? null : requested.strip();
        if (a == null) {
            Environment env = environments.getEnvironment(envId);
            a = isBlank(env.getUsername()) ? "unknown" : env.getUsername();
        }
        return a.length() > MAX_AUTHOR_LENGTH ? a.substring(0, MAX_AUTHOR_LENGTH) : a;
    }

    private static String requireMessage(String message) {
        if (isBlank(message)) {
            throw ApiException.badRequest("commit.messageRequired");
        }
        return message.strip();
    }

    private static String summary(List<ChangeItem> changes) {
        String s = changes.stream().limit(5)
                .map(c -> c.getChangeType() + " " + c.getObjectType() + " " + c.getObjectName())
                .collect(Collectors.joining("; "));
        return changes.size() > 5 ? s + "; ..." : s;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String shortHash(String hash) {
        return hash == null ? "<none>" : hash.substring(0, Math.min(10, hash.length()));
    }
}
