package com.vodianoy.pgvc.vcs;

import com.vodianoy.pgvc.common.ApiException;
import com.vodianoy.pgvc.storage.CommitRecord;
import com.vodianoy.pgvc.storage.PgVcStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Walks the commit graph of one environment along parent links. */
@Component
@RequiredArgsConstructor
public class CommitHistory {

    private final PgVcStorageService storage;

    /**
     * Commits after {@code fromExclusive} up to and including {@code toInclusive}, oldest first.
     * {@code fromExclusive == null} means from the very first commit.
     *
     * @throws ApiException CONFLICT when {@code fromExclusive} is not an ancestor of {@code toInclusive}
     */
    public List<CommitRecord> range(String envId, String fromExclusive, String toInclusive) {
        if (toInclusive == null || toInclusive.isBlank()) {
            throw ApiException.badRequest("history.targetRequired");
        }
        if (toInclusive.equals(fromExclusive)) {
            return List.of();
        }
        if (fromExclusive != null) {
            storage.requireCommit(envId, fromExclusive);
        }
        List<CommitRecord> chain = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String current = toInclusive;
        while (current != null && !current.equals(fromExclusive)) {
            if (!seen.add(current)) {
                throw ApiException.conflict("history.cycle", shortHash(current));
            }
            CommitRecord c = storage.requireCommit(envId, current);
            chain.add(c);
            current = c.getParentHash();
        }
        if (fromExclusive != null && current == null) {
            throw ApiException.conflict("history.notAncestor", shortHash(fromExclusive), shortHash(toInclusive), envId);
        }
        Collections.reverse(chain);
        return chain;
    }

    /** {@code hash} and all its ancestors, newest first. */
    public Set<String> ancestors(String envId, String hash) {
        Set<String> result = new LinkedHashSet<>();
        String current = hash;
        while (current != null && result.add(current)) {
            current = storage.findCommit(envId, current).map(CommitRecord::getParentHash).orElse(null);
        }
        return result;
    }

    static String shortHash(String hash) {
        return hash == null ? "<none>" : hash.substring(0, Math.min(10, hash.length()));
    }
}
