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
import com.vodianoy.pgvc.storage.PgVcStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
                throw ApiException.conflict("HEAD of '" + envId + "' has moved to " + shortHash(head)
                        + " since the working tree was read. Refresh and review the changes again.");
            }
            DatabaseSnapshot live = introspector.introspect(envId);
            SchemaDiffResult diff = diffEngine.diff(headSnapshot(envId, head), live);
            if (!diff.isHasChanges() && isBlank(request.upSql())) {
                throw ApiException.badRequest("Nothing to commit: the database schema matches HEAD");
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
            throw ApiException.badRequest("UP SQL is empty");
        }
        if (TRANSACTION_CONTROL.matcher(request.upSql()).find()) {
            throw ApiException.badRequest("UP SQL must not contain BEGIN / COMMIT / ROLLBACK: the migration runs inside"
                    + " one transaction managed by PgGitVisualizer");
        }
        return environments.getTransactionTemplate(envId).execute(tx -> {
            storage.lockHistory(envId);
            String head = storage.getHead(envId).orElse(null);
            DatabaseSnapshot before = introspector.introspect(envId);
            SchemaDiffResult drift = diffEngine.diff(headSnapshot(envId, head), before);
            if (drift.isHasChanges() && !request.allowDirty()) {
                throw ApiException.conflict("The database has " + drift.getChanges().size() + " uncommitted schema"
                        + " change(s) (" + summary(drift.getChanges()) + "). Commit them first or allow including them.");
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
            throw ApiException.badRequest("targetHash is required");
        }
        if (!isBlank(request.customRollbackSql()) && TRANSACTION_CONTROL.matcher(request.customRollbackSql()).find()) {
            throw ApiException.badRequest("Rollback SQL must not contain BEGIN / COMMIT / ROLLBACK");
        }
        return environments.getTransactionTemplate(envId).execute(tx -> {
            storage.lockHistory(envId);
            String head = storage.getHead(envId).orElseThrow(() ->
                    ApiException.conflict("Environment '" + envId + "' has no commits to roll back"));
            List<CommitRecord> chain = chainToAncestor(envId, head, request.targetHash());
            CommitRecord target = storage.requireCommit(envId, request.targetHash());

            if (!request.allowDirty()) {
                SchemaDiffResult drift = diffEngine.diff(headSnapshot(envId, head), introspector.introspect(envId));
                if (drift.isHasChanges()) {
                    throw ApiException.conflict("The database has uncommitted schema changes (" + summary(drift.getChanges())
                            + "). Commit or revert them before rolling back.");
                }
            }

            String sql = isBlank(request.customRollbackSql()) ? rollbackSql(chain) : request.customRollbackSql();
            environments.getJdbcTemplate(envId).execute(sql);

            boolean verify = !request.skipVerification();
            if (verify) {
                SchemaDiffResult rest = diffEngine.diff(parseSnapshot(target.getSchemaSnapshotJson()),
                        introspector.introspect(envId));
                if (rest.isHasChanges()) {
                    throw ApiException.conflict("After the rollback SQL the schema still differs from commit "
                            + shortHash(target.getHash()) + ": " + summary(rest.getChanges())
                            + ". Nothing was changed. Adjust the rollback SQL.");
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
                ApiException.conflict("Environment '" + envId + "' has no commits to roll back"));
        List<CommitRecord> chain = chainToAncestor(envId, head, targetHash);
        return new RollbackPlan(head, targetHash, chain.stream().map(CommitRecord::toNode).toList(), rollbackSql(chain));
    }

    // ------------------------------------------------------------------ commits

    public CommitDetails getCommit(String envId, String hash) {
        CommitRecord c = storage.requireCommit(envId, hash);
        return new CommitDetails(c.getHash(), c.getParentHash(), c.getAuthor(), c.getMessage(), c.getCreatedAt(),
                c.getUpSql(), c.getDownSql(), parseSnapshot(c.getSchemaSnapshotJson()), parseJson(c.getDdlLogHistoryJson()));
    }

    // ------------------------------------------------------------------ helpers

    /** Commits from HEAD (inclusive) down to the target (exclusive), newest first. */
    private List<CommitRecord> chainToAncestor(String envId, String head, String targetHash) {
        if (targetHash == null || targetHash.isBlank()) {
            throw ApiException.badRequest("targetHash is required");
        }
        if (targetHash.equals(head)) {
            throw ApiException.badRequest("The environment is already at commit " + shortHash(targetHash));
        }
        storage.requireCommit(envId, targetHash);
        List<CommitRecord> chain = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String current = head;
        while (current != null && !current.equals(targetHash)) {
            if (!seen.add(current)) {
                throw ApiException.conflict("Cycle in the commit history at " + shortHash(current));
            }
            CommitRecord c = storage.requireCommit(envId, current);
            chain.add(c);
            current = c.getParentHash();
        }
        if (current == null) {
            throw ApiException.conflict("Commit " + shortHash(targetHash) + " is not an ancestor of HEAD "
                    + shortHash(head) + ": only earlier commits of the current history can be restored");
        }
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
            throw ApiException.badRequest("Commit message is required");
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
