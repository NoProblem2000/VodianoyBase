package com.vodianoy.pgvc.vcs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vodianoy.pgvc.common.ApiException;
import com.vodianoy.pgvc.diff.ChangeItem;
import com.vodianoy.pgvc.diff.ChangeType;
import com.vodianoy.pgvc.diff.CrossSchemaSequenceCheck;
import com.vodianoy.pgvc.diff.QuickFix;
import com.vodianoy.pgvc.diff.DiffMode;
import com.vodianoy.pgvc.diff.ObjectType;
import com.vodianoy.pgvc.diff.SchemaDiffEngine;
import com.vodianoy.pgvc.diff.SchemaDiffResult;
import com.vodianoy.pgvc.diff.SnapshotFilter;
import com.vodianoy.pgvc.environment.Environment;
import com.vodianoy.pgvc.environment.EnvironmentManagerService;
import com.vodianoy.pgvc.execution.MigrationRunner;
import com.vodianoy.pgvc.execution.MigrationStep;
import com.vodianoy.pgvc.execution.StepReport;
import com.vodianoy.pgvc.execution.Timeouts;
import com.vodianoy.pgvc.execution.SqlScripts;
import com.vodianoy.pgvc.execution.TransactionalScript;
import com.vodianoy.pgvc.safety.MigrationSafetyAnalyzer;
import com.vodianoy.pgvc.safety.SqlSafetyReport;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
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
    private static final int SNAPSHOT_CACHE_SIZE = 16;

    /** JSON of recently used HEAD snapshots by {@code envId|jdbcUrl|hash} (least recently used evicted). */
    private final Map<String, String> snapshotJsonCache = new LinkedHashMap<>(SNAPSHOT_CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > SNAPSHOT_CACHE_SIZE;
        }
    };

    private final EnvironmentManagerService environments;
    private final PgVcStorageService storage;
    private final PostgresSchemaIntrospector introspector;
    private final SchemaDiffEngine diffEngine;
    private final ObjectMapper objectMapper;
    private final CommitHistory history;
    private final MigrationRunner runner;
    private final MigrationSafetyAnalyzer safety;

    // ------------------------------------------------------------------ Mode A

    /** HEAD snapshot vs live schema, plus the DDL captured since the last commit. */
    public WorkingTreeStatus getWorkingTreeStatus(String envId) {
        return getWorkingTreeStatus(envId, false);
    }

    /**
     * HEAD snapshot vs live schema, plus the DDL captured since the last commit.
     *
     * @param includeSnapshot also return the live snapshot (the schema explorer of the UI needs it anyway)
     */
    public WorkingTreeStatus getWorkingTreeStatus(String envId, boolean includeSnapshot) {
        long started = System.nanoTime();
        Optional<String> head = storage.getHead(envId);
        DatabaseSnapshot base = headSnapshot(envId, head.orElse(null));
        long introspectionStarted = System.nanoTime();
        DatabaseSnapshot live = introspector.introspect(envId);
        long introspectionMs = (System.nanoTime() - introspectionStarted) / 1_000_000;
        SchemaDiffResult diff = diffEngine.diff(base, live);
        // how the UP SQL would behave on a database still at HEAD (structure of HEAD, current table sizes); the DOWN
        // SQL (discard / rollback of the commit) runs on the live database
        safety.annotate(diff, MigrationSafetyAnalyzer.withSizes(base, live), live);
        // defaults still using the sequence of another schema (CREATE TABLE ... LIKE ... INCLUDING ALL)
        CrossSchemaSequenceCheck.apply(diff, live);
        List<AuditLogEntry> audit = storage.readAuditLog(envId);
        long totalMs = (System.nanoTime() - started) / 1_000_000;
        log.debug("Working tree of {}: {} ms (introspection {} ms, {} relations)", envId, totalMs, introspectionMs,
                live.getTables().size() + live.getViews().size() + live.getSequences().size());
        return new WorkingTreeStatus(envId, head.orElse(null), diff, audit, introspectionMs, totalMs,
                includeSnapshot ? live : null);
    }

    // ------------------------------------------------------------------ quick fixes

    /**
     * Runs one quick fix of the live schema (e.g. points a default to the sequence of its own schema) in one
     * transaction, without a commit: the change then appears in the working tree like any other DDL. The SQL is the one
     * the server finds for {@code objectName} now - never SQL sent by the client.
     */
    public QuickFixResult applyQuickFix(QuickFixRequest request) {
        String envId = requireEnvId(request == null ? null : request.envId());
        if (request.objectName() == null || request.objectName().isBlank()) {
            throw ApiException.badRequest("quickFix.objectRequired");
        }
        DatabaseSnapshot live = introspector.introspect(envId);
        QuickFix fix = CrossSchemaSequenceCheck.quickFixes(live).stream()
                .filter(f -> f.objectName().equals(request.objectName().strip())
                        && (request.kind() == null || request.kind().equals(f.kind())))
                .findFirst()
                .orElseThrow(() -> ApiException.conflict("quickFix.notFound", request.objectName().strip()));
        Timeouts timeouts = runner.timeouts(request.lockTimeout(), request.statementTimeout());
        environments.getTransactionTemplate(envId).executeWithoutResult(status -> {
            runner.applyLocal(envId, timeouts);
            runner.execute(envId, fix.sql(), timeouts);
        });
        log.info("Quick fix {} applied to {} in {}", fix.kind(), fix.objectName(), envId);
        return new QuickFixResult(envId, fix.objectName(), fix.sql());
    }

    // ------------------------------------------------------------------ discard (reset to HEAD)

    /**
     * SQL that reverts uncommitted changes: all of them (the database returns to HEAD, or to an empty schema when
     * there are no commits) or the selected ones (positions in the working-tree change list). Statements come from
     * the reverse diff "live -> HEAD" in its dependency-safe order.
     */
    public DiscardPlan discardPlan(DiscardPlanRequest request) {
        String envId = requireEnvId(request == null ? null : request.envId());
        Optional<String> head = storage.getHead(envId);
        return plan(envId, head.orElse(null), introspector.introspect(envId),
                request.changeIndexes());
    }

    private DiscardPlan plan(String envId, String head, DatabaseSnapshot live, List<Integer> changeIndexes) {
        DatabaseSnapshot base = headSnapshot(envId, head);
        // compare() numbers the changes and keeps the DOWN script statement by statement; same order as the
        // working tree (diff) shows
        SchemaDiffResult diff = diffEngine.compare(base, live, false, null, DiffMode.APPLIED);
        List<ChangeItem> all = diff.getChanges();
        List<Integer> ids = new ArrayList<>();
        if (changeIndexes == null || changeIndexes.isEmpty()) {
            for (int i = 0; i < all.size(); i++) {
                ids.add(i);
            }
        } else {
            for (Integer i : new java.util.LinkedHashSet<>(changeIndexes)) {
                if (i == null || i < 0 || i >= all.size()) {
                    throw ApiException.badRequest("discard.badIndex", String.valueOf(i), all.size());
                }
                ids.add(i);
            }
        }
        boolean full = ids.size() == all.size();
        String sql = SchemaDiffEngine.assemble(diff.getDownStatements(), ids);
        List<ChangeItem> selected = ids.stream().map(all::get).toList();
        return new DiscardPlan(envId, head, full, all.size(), selected, sql, safety.analyzeSql(sql, live),
                storage.readAuditLog(envId).size());
    }

    /**
     * Discards uncommitted changes in one transaction: runs the generated (or the user's) DOWN SQL; on a full reset
     * checks that the schema equals HEAD again and clears _pgvc.ddl_audit_log; on a partial one removes only the
     * audit entries the discard itself produced. Any error or mismatch rolls everything back. HEAD does not move.
     */
    public DiscardResult discardWorkingTree(DiscardRequest request) {
        String envId = requireEnvId(request == null ? null : request.envId());
        if (!isBlank(request.customDownSql()) && TRANSACTION_CONTROL.matcher(request.customDownSql()).find()) {
            throw ApiException.badRequest("rollback.transactionControl");
        }
        Timeouts timeouts = runner.timeouts(request.lockTimeout(), request.statementTimeout());
        return environments.getTransactionTemplate(envId).execute(tx -> {
            runner.applyLocal(envId, timeouts);
            storage.lockHistory(envId);
            String head = storage.getHead(envId).orElse(null);
            DiscardPlan plan = plan(envId, head, introspector.introspect(envId), request.changeIndexes());
            if (plan.totalChanges() == 0) {
                throw ApiException.badRequest("discard.nothing", envId);
            }
            if (request.expectedTotalChanges() != null && request.expectedTotalChanges() != plan.totalChanges()) {
                throw ApiException.conflict("discard.changed", envId, request.expectedTotalChanges(), plan.totalChanges());
            }
            String sql = isBlank(request.customDownSql()) ? plan.downSql() : request.customDownSql();
            if (isBlank(sql) || SqlScripts.statements(sql).isEmpty()) {
                throw ApiException.badRequest("discard.noSql");
            }
            long auditBefore = storage.maxAuditLogId(envId);
            // one transaction: CONCURRENTLY statements run as plain ones
            runner.execute(envId, TransactionalScript.of(sql).sql(), timeouts);

            SchemaDiffResult rest = diffEngine.diff(headSnapshot(envId, head), introspector.introspect(envId));
            boolean verify = plan.full() && !request.skipVerification();
            if (verify && rest.isHasChanges()) {
                throw ApiException.conflict("discard.verifyFailed", envId, summary(rest.getChanges()));
            }
            int removed = plan.full() ? storage.clearAuditLog(envId) : storage.clearAuditLogAfter(envId, auditBefore);
            log.info("Discarded {} of {} uncommitted change(s) in {} ({} left, {} audit entries removed)",
                    plan.changes().size(), plan.totalChanges(), envId, rest.getChanges().size(), removed);
            return new DiscardResult(envId, head, plan.full(), plan.changes().size(), rest.getChanges().size(), sql,
                    removed, verify);
        });
    }

    /** Only empties _pgvc.ddl_audit_log (e.g. stale trigger events); the schema is not touched. */
    public ClearLogResult clearDdlLog(String envId) {
        String id = requireEnvId(envId);
        int removed = storage.clearAuditLog(id);
        log.info("Cleared {} DDL audit entries of {}", removed, id);
        return new ClearLogResult(id, removed);
    }

    private String requireEnvId(String envId) {
        if (isBlank(envId)) {
            throw ApiException.badRequest("env.idRequired");
        }
        environments.getEnvironment(envId);
        return envId.strip();
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
            CommitRecord commit = newCommit(head, author, message, request.description(),
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
     * Executes the migration ({@code preScript} + {@code upSql} + {@code postScript}) with {@code lock_timeout} /
     * {@code statement_timeout}, reads the new schema, generates DOWN SQL when none was given and stores the commit.
     * <ul>
     *   <li>One step (no {@code -- pgvc:step} markers, no CONCURRENTLY): everything - including the commit record - runs
     *       in one transaction; any error rolls everything back.</li>
     *   <li>Several steps: every step is a transaction of its own (a CONCURRENTLY step runs outside of a transaction);
     *       the commit is recorded after the last one. If a step fails, the steps before it stay applied and show up
     *       as uncommitted changes (Working Tree).</li>
     * </ul>
     * With {@code dryRun} see {@link #dryRun}.
     */
    public CommitResult executeAndCommitSql(String envId, ExecuteMigrationRequest request) {
        if (request.dryRun()) {
            throw new IllegalArgumentException("Use dryRun() for a dry-run request");
        }
        String message = requireMessage(request.message());
        String author = author(request.author(), envId);
        String script = migrationScript(request);
        Timeouts timeouts = runner.timeouts(request.lockTimeout(), request.statementTimeout());
        List<MigrationStep> steps = MigrationStep.plan(script);
        if (MigrationStep.isMultiStep(steps)) {
            return executeSteps(envId, request, script, steps, timeouts, message, author);
        }
        return environments.getTransactionTemplate(envId).execute(tx -> {
            runner.applyLocal(envId, timeouts);
            storage.lockHistory(envId);
            String head = storage.getHead(envId).orElse(null);
            DatabaseSnapshot before = introspector.introspect(envId);
            requireClean(envId, head, before, request.allowDirty());

            long elapsed = runner.execute(envId, script, timeouts);

            DatabaseSnapshot after = introspector.introspect(envId);
            SchemaDiffResult change = diffEngine.diff(before, after, null, DiffMode.RECORDED);
            String downSql = isBlank(request.downSql()) ? change.getDownSql() : request.downSql();
            List<AuditLogEntry> audit = storage.readAuditLog(envId);
            CommitRecord commit = newCommit(head, author, message, request.description(), script, downSql, after, audit);
            storage.insertCommit(envId, commit);
            storage.setHead(envId, commit.getHash());
            storage.clearAuditLog(envId);
            log.info("Executed and committed {} on {} in {} ms: {}", shortHash(commit.getHash()), envId, elapsed, message);
            return new CommitResult(commit.toNode(), change.getChanges(),
                    List.of(new StepReport(1, true, steps.isEmpty() ? 0 : steps.get(0).statements().size(), elapsed)),
                    elapsed);
        });
    }

    private CommitResult executeSteps(String envId, ExecuteMigrationRequest request, String script,
                                      List<MigrationStep> steps, Timeouts timeouts, String message, String author) {
        // 1. preconditions, and the schema before the first step
        record Start(String head, DatabaseSnapshot before) {
        }
        Start start = environments.getTransactionTemplate(envId).execute(tx -> {
            runner.applyLocal(envId, timeouts);
            storage.lockHistory(envId);
            String head = storage.getHead(envId).orElse(null);
            DatabaseSnapshot before = introspector.introspect(envId);
            requireClean(envId, head, before, request.allowDirty());
            return new Start(head, before);
        });

        // 2. the steps, each in its own transaction (or outside of one)
        List<StepReport> reports = new ArrayList<>();
        long elapsed = 0;
        for (MigrationStep step : steps) {
            try {
                StepReport r = runner.run(envId, step, timeouts);
                reports.add(r);
                elapsed += r.elapsedMs();
            } catch (RuntimeException e) {
                Object cause = MigrationRunner.messageArgument(e);
                if (step.index() == 1) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "migration.stepFailedFirst", steps.size(), cause);
                }
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "migration.stepFailed", step.index(),
                        steps.size(), cause, step.index() - 1);
            }
        }

        // 3. record the commit
        long total = elapsed;
        return environments.getTransactionTemplate(envId).execute(tx -> {
            runner.applyLocal(envId, timeouts);
            storage.lockHistory(envId);
            String head = storage.getHead(envId).orElse(null);
            if (!Objects.equals(head, start.head())) {
                throw ApiException.conflict("migration.headMovedAfterSteps", envId, shortHash(head));
            }
            DatabaseSnapshot after = introspector.introspect(envId);
            SchemaDiffResult change = diffEngine.diff(start.before(), after, null, DiffMode.RECORDED);
            String downSql = isBlank(request.downSql()) ? change.getDownSql() : request.downSql();
            List<AuditLogEntry> audit = storage.readAuditLog(envId);
            CommitRecord commit = newCommit(head, author, message, request.description(), script, downSql, after, audit);
            storage.insertCommit(envId, commit);
            storage.setHead(envId, commit.getHash());
            storage.clearAuditLog(envId);
            log.info("Executed {} steps and committed {} on {} in {} ms: {}", steps.size(), shortHash(commit.getHash()),
                    envId, total, message);
            return new CommitResult(commit.toNode(), change.getChanges(), reports, total);
        });
    }

    /**
     * Dry-run: {@code preScript} + {@code upSql} + {@code postScript} run on the real database with the requested
     * timeouts inside ONE transaction that is always rolled back. Reports success or the error, the real time per
     * step, the schema changes and the generated DOWN SQL; nothing is stored. Statements that cannot run inside a
     * transaction run in a modified form (CONCURRENTLY removed) and are listed.
     */
    public DryRunResult dryRun(String envId, ExecuteMigrationRequest request) {
        String script = migrationScript(request);
        Timeouts timeouts = runner.timeouts(request.lockTimeout(), request.statementTimeout());
        List<MigrationStep> original = MigrationStep.plan(script);
        TransactionalScript transactional = TransactionalScript.of(script);
        List<MigrationStep> steps = MigrationStep.plan(transactional.sql());

        return environments.getTransactionTemplate(envId).execute(tx -> {
            tx.setRollbackOnly();
            runner.applyLocal(envId, timeouts);
            String head = storage.getStatus(envId).initialized() ? storage.getHead(envId).orElse(null) : null;
            DatabaseSnapshot before = introspector.introspect(envId);
            int uncommitted = head == null && before.isEmpty() ? 0
                    : diffEngine.diff(headSnapshot(envId, head), before).getChanges().size();
            SqlSafetyReport report = safety.analyzeSql(script, before);

            List<StepReport> reports = new ArrayList<>();
            long elapsed = 0;
            for (MigrationStep step : steps) {
                long t0 = System.nanoTime();
                try {
                    runner.execute(envId, step.sql(), timeouts);
                } catch (RuntimeException e) {
                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    reports.add(new StepReport(step.index(), stepTransactional(original, step.index()),
                            step.statements().size(), ms));
                    return new DryRunResult(false, elapsed + ms, reports, steps.size(), step.index(), runner.describe(e),
                            List.of(), null, transactional.rewritten(), uncommitted, report, timeouts);
                }
                long ms = (System.nanoTime() - t0) / 1_000_000;
                elapsed += ms;
                reports.add(new StepReport(step.index(), stepTransactional(original, step.index()),
                        step.statements().size(), ms));
            }
            DatabaseSnapshot after = introspector.introspect(envId);
            SchemaDiffResult change = diffEngine.diff(before, after, null, DiffMode.PLANNED);
            log.info("Dry-run on {}: {} step(s) in {} ms, {} change(s), rolled back", envId, steps.size(), elapsed,
                    change.getChanges().size());
            return new DryRunResult(true, elapsed, reports, steps.size(), null, null, change.getChanges(),
                    change.getDownSql(), transactional.rewritten(), uncommitted, report, timeouts);
        });
    }

    private static boolean stepTransactional(List<MigrationStep> original, int index) {
        return index > original.size() || original.get(index - 1).transactional();
    }

    /** {@code preScript} + {@code upSql} + {@code postScript}; validated (no transaction control, UP not empty). */
    static String migrationScript(ExecuteMigrationRequest request) {
        if (isBlank(request.upSql())) {
            throw ApiException.badRequest("migration.upSqlEmpty");
        }
        for (String part : new String[]{request.preScript(), request.upSql(), request.postScript()}) {
            if (!isBlank(part) && TRANSACTION_CONTROL.matcher(part).find()) {
                throw ApiException.badRequest("migration.transactionControl");
            }
        }
        if (isBlank(request.preScript()) && isBlank(request.postScript())) {
            return request.upSql();
        }
        StringBuilder sb = new StringBuilder();
        if (!isBlank(request.preScript())) {
            sb.append("-- pgvc:pre-script\n").append(request.preScript().strip()).append("\n\n");
        }
        sb.append(request.upSql().strip()).append('\n');
        if (!isBlank(request.postScript())) {
            sb.append("\n-- pgvc:post-script\n").append(request.postScript().strip()).append('\n');
        }
        return sb.toString();
    }

    /** Refuses to run on top of uncommitted schema changes unless allowed. */
    private void requireClean(String envId, String head, DatabaseSnapshot live, boolean allowDirty) {
        SchemaDiffResult drift = diffEngine.diff(headSnapshot(envId, head), live);
        if (drift.isHasChanges() && !allowDirty) {
            throw ApiException.conflict("migration.dirty", drift.getChanges().size(), summary(drift.getChanges()));
        }
    }

    /** Safety of an arbitrary script against the live schema (and table sizes) of the environment. */
    public SqlSafetyReport analyzeSql(String envId, String sql) {
        return safety.analyzeSql(sql, introspector.introspect(envId));
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
        Timeouts timeouts = runner.timeouts(request.lockTimeout(), request.statementTimeout());
        return environments.getTransactionTemplate(envId).execute(tx -> {
            runner.applyLocal(envId, timeouts);
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
            // a rollback is one transaction: CONCURRENTLY statements run as plain ones
            runner.execute(envId, TransactionalScript.of(sql).sql(), timeouts);

            boolean verify = !request.skipVerification();
            if (verify) {
                DatabaseSnapshot targetSnapshot = parseSnapshot(target.getSchemaSnapshotJson());
                SchemaDiffResult rest = diffEngine.diff(targetSnapshot,
                        SnapshotFilter.withoutDataUntrackedIn(targetSnapshot, introspector.introspect(envId)));
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

    // ------------------------------------------------------------------ revert of one commit (git revert)

    /**
     * What reverting {@code targetHash} would execute: its DOWN SQL on top of the current HEAD. Lists the tables /
     * columns whose structure comes back without the rows deleted then, the ones that are dropped again with their
     * data, and objects that later commits changed as well (the SQL may need editing).
     */
    public RevertPlan getRevertPlan(String envId, String targetHash) {
        String head = storage.getHead(envId).orElseThrow(() -> ApiException.conflict("revert.noCommits", envId));
        CommitRecord target = requireInHistory(envId, head, targetHash);
        List<CommitRecord> later = history.range(envId, target.getHash(), head);
        SchemaDiffResult changes = getCommitChanges(envId, target.getHash());
        Set<String> touched = touchedObjects(changes);
        Set<String> conflicts = new TreeSet<>();
        for (CommitRecord c : later) {
            Set<String> again = touchedObjects(getCommitChanges(envId, c.getHash()));
            again.retainAll(touched);
            conflicts.addAll(again);
        }
        List<String> restored = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        for (ChangeItem c : changes.getChanges()) {
            boolean holdsData = c.getObjectType() == ObjectType.TABLE || c.getObjectType() == ObjectType.PARTITION
                    || c.getObjectType() == ObjectType.COLUMN;
            if (holdsData && c.getChangeType() == ChangeType.DELETED) {
                restored.add(c.getObjectName());
            } else if (holdsData && c.getChangeType() == ChangeType.ADDED) {
                dropped.add(c.getObjectName());
            }
        }
        DatabaseSnapshot live = introspector.introspect(envId);
        boolean dirty = diffEngine.diff(headSnapshot(envId, head), live).isHasChanges();
        String sql = target.getDownSql();
        return new RevertPlan(envId, head, target.toNode(), head.equals(target.getHash()), later.size(), sql,
                target.getUpSql(), revertMessage(target), changes.getChanges(), restored, dropped,
                new ArrayList<>(conflicts), target.getParentHash() == null, safety.analyzeSql(sql, live), dirty);
    }

    /**
     * Reverts one commit: runs its DOWN SQL (or the reviewed {@code customRevertSql}) with the migration timeouts,
     * reads the new schema and records a new commit on top of HEAD whose DOWN SQL is the UP SQL of the reverted
     * commit - all in one transaction (see {@link #executeAndCommitSql}); the DDL log is cleared.
     */
    public CommitResult revertCommit(RevertRequest request) {
        if (request == null || isBlank(request.envId())) {
            throw ApiException.badRequest("env.idRequired");
        }
        String envId = request.envId();
        String head = storage.getHead(envId).orElseThrow(() -> ApiException.conflict("revert.noCommits", envId));
        if (!isBlank(request.expectedHead()) && !request.expectedHead().equals(head)) {
            throw ApiException.conflict("commit.headMoved", envId, shortHash(head));
        }
        CommitRecord target = requireInHistory(envId, head, request.targetCommitHash());
        String original = target.getDownSql() == null ? "" : target.getDownSql();
        boolean edited = !isBlank(request.customRevertSql()) && !request.customRevertSql().strip().equals(original.strip());
        String sql = edited ? request.customRevertSql() : original;
        if (!hasStatements(sql)) {
            throw ApiException.badRequest("revert.nothing", shortHash(target.getHash()));
        }
        String message = isBlank(request.message()) ? revertMessage(target) : request.message().strip();
        String description = request.description() == null
                ? "Reverts commit " + shortHash(target.getHash()) + "." : request.description();
        // an edited script is not the exact inverse of the target any more: its DOWN SQL comes from the schema diff
        ExecuteMigrationRequest migration = new ExecuteMigrationRequest(message, request.author(), sql,
                edited ? null : target.getUpSql(), request.allowDirty(), request.lockTimeout(), request.statementTimeout(),
                null, null, false, description);
        CommitResult result = executeAndCommitSql(envId, migration);
        log.info("Reverted {} on {} with commit {}", shortHash(target.getHash()), envId, shortHash(result.commit().hash()));
        return result;
    }

    private CommitRecord requireInHistory(String envId, String head, String targetHash) {
        if (isBlank(targetHash)) {
            throw ApiException.badRequest("revert.targetRequired");
        }
        CommitRecord target = storage.requireCommit(envId, targetHash.strip());
        if (!history.ancestors(envId, head).contains(target.getHash())) {
            throw ApiException.conflict("revert.notInHistory", shortHash(target.getHash()), envId, shortHash(head));
        }
        return target;
    }

    private static String revertMessage(CommitRecord target) {
        String first = target.getMessage() == null ? "" : target.getMessage().strip().split("\\R", 2)[0];
        return "Revert \"" + first + "\"";
    }

    /** Objects (tables, views, functions ...) a change list touches: the root object of every change. */
    private static Set<String> touchedObjects(SchemaDiffResult diff) {
        Set<String> names = new HashSet<>();
        for (ChangeItem c : diff.getChanges()) {
            names.add(c.getRootName() != null ? c.getRootName() : c.getObjectName());
        }
        return names;
    }

    /** The script contains something other than comments and blank lines. */
    private static boolean hasStatements(String sql) {
        return sql != null && sql.lines().map(String::strip).anyMatch(l -> !l.isEmpty() && !l.startsWith("--"));
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
        // two different databases: objects are matched by name only (their OIDs / attnums are unrelated)
        SchemaDiffResult schemaDiff = diffEngine.diffAcrossEnvironments(targetLive, headSnapshot(sourceEnvId, sourceHead));
        safety.annotate(schemaDiff, targetLive);
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
            DatabaseSnapshot snapshot = parseSnapshot(c.getSchemaSnapshotJson());
            if (!diffEngine.diffAcrossEnvironments(snapshot, SnapshotFilter.withoutDataUntrackedIn(snapshot, schema)).isHasChanges()) {
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

            DatabaseSnapshot commitSnapshot = parseSnapshot(commit.getSchemaSnapshotJson());
            SchemaDiffResult diff = linkDifferences(commitSnapshot, live);
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

    /**
     * Differences between the live schema of a target and a commit snapshot, from the point of view of the target:
     * UP = live target -&gt; commit (what the target lacks or has differently), DOWN = back. That is the SQL a Discard
     * runs after linking with accepted differences. Objects are matched by name (two different databases).
     */
    private SchemaDiffResult linkDifferences(DatabaseSnapshot commitSnapshot, DatabaseSnapshot live) {
        return diffEngine.diffAcrossEnvironments(SnapshotFilter.withoutDataUntrackedIn(commitSnapshot, live), commitSnapshot);
    }

    /** What linking {@code targetEnvId} to a commit of {@code sourceEnvId} would leave as uncommitted changes. */
    public LinkPreview linkPreview(String sourceEnvId, String targetEnvId, String commitHash) {
        requireDifferent(sourceEnvId, targetEnvId);
        String sourceHead = storage.getHead(sourceEnvId).orElseThrow(() ->
                ApiException.badRequest("link.noCommits", sourceEnvId));
        String hash = isBlank(commitHash) ? sourceHead : commitHash.strip();
        CommitRecord commit = storage.requireCommit(sourceEnvId, hash);
        if (!history.ancestors(sourceEnvId, sourceHead).contains(hash)) {
            throw ApiException.badRequest("link.notInHistory", shortHash(hash), sourceEnvId, shortHash(sourceHead));
        }
        PgVcStatus target = requireReachable(targetEnvId);
        DatabaseSnapshot live = introspector.introspect(targetEnvId);
        SchemaDiffResult diff = linkDifferences(parseSnapshot(commit.getSchemaSnapshotJson()), live);
        safety.annotate(diff, live);
        return new LinkPreview(sourceEnvId, targetEnvId, commit.toNode(), !diff.isHasChanges(), target.initialized(), diff);
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
        Timeouts timeouts = runner.timeouts(request.lockTimeout(), request.statementTimeout());
        boolean verify = !request.skipVerification();

        // commits written as several steps (zero-downtime patterns, CONCURRENTLY) are pushed one by one
        String knownHead = storage.getHead(targetEnvId).orElse(null);
        List<CommitRecord> preview = knownHead == null || history.ancestors(sourceEnvId, tip).contains(knownHead)
                ? history.range(sourceEnvId, knownHead, tip) : List.of();
        if (preview.stream().anyMatch(c -> MigrationStep.isMultiStep(MigrationStep.plan(c.getUpSql())))) {
            return pushStepwise(request, tip, timeouts, verify);
        }

        return environments.getTransactionTemplate(targetEnvId).execute(tx -> {
            runner.applyLocal(targetEnvId, timeouts);
            storage.lockHistory(targetEnvId);
            String targetHead = storage.getHead(targetEnvId).orElse(null);
            List<CommitRecord> commits = commitsToPush(request, tip, targetHead);

            for (CommitRecord c : commits) {
                applyCommitSql(targetEnvId, c, c.getUpSql(), timeouts);
                storage.insertCommit(targetEnvId, c);
            }
            storage.setHead(targetEnvId, tip);
            storage.clearAuditLog(targetEnvId);
            if (verify) {
                verifyPushed(targetEnvId, commits.get(commits.size() - 1));
            }
            log.info("Pushed {} commit(s) {} -> {}: HEAD {} -> {}", commits.size(), sourceEnvId, targetEnvId,
                    shortHash(targetHead), shortHash(tip));
            return new PushResult(sourceEnvId, targetEnvId, targetHead, tip,
                    commits.stream().map(CommitRecord::toNode).toList(), verify);
        });
    }

    /**
     * Commit by commit: a single-step commit in one transaction together with its record; a multi-step commit step by
     * step (CONCURRENTLY outside of a transaction), then its record. HEAD advances after every commit, so a failure
     * leaves the target at the last fully pushed commit (and the applied steps of the failed one as uncommitted
     * changes).
     */
    private PushResult pushStepwise(PushRequest request, String tip, Timeouts timeouts, boolean verify) {
        String sourceEnvId = request.sourceEnvironmentId();
        String targetEnvId = request.targetEnvironmentId();
        record Start(String head, List<CommitRecord> commits) {
        }
        Start start = environments.getTransactionTemplate(targetEnvId).execute(tx -> {
            runner.applyLocal(targetEnvId, timeouts);
            storage.lockHistory(targetEnvId);
            String targetHead = storage.getHead(targetEnvId).orElse(null);
            return new Start(targetHead, commitsToPush(request, tip, targetHead));
        });

        String expected = start.head();
        List<CommitRecord> pushed = new ArrayList<>();
        for (CommitRecord c : start.commits()) {
            List<MigrationStep> steps = MigrationStep.plan(c.getUpSql());
            String parent = expected;
            if (!MigrationStep.isMultiStep(steps)) {
                environments.getTransactionTemplate(targetEnvId).executeWithoutResult(tx -> {
                    runner.applyLocal(targetEnvId, timeouts);
                    storage.lockHistory(targetEnvId);
                    requireHead(targetEnvId, parent);
                    applyCommitSql(targetEnvId, c, c.getUpSql(), timeouts);
                    recordPushed(targetEnvId, c, verify);
                });
            } else {
                for (MigrationStep step : steps) {
                    try {
                        runner.run(targetEnvId, step, timeouts);
                    } catch (RuntimeException e) {
                        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "push.stepFailed", shortHash(c.getHash()),
                                c.getMessage(), step.index(), steps.size(), targetEnvId, MigrationRunner.messageArgument(e));
                    }
                }
                environments.getTransactionTemplate(targetEnvId).executeWithoutResult(tx -> {
                    runner.applyLocal(targetEnvId, timeouts);
                    storage.lockHistory(targetEnvId);
                    requireHead(targetEnvId, parent);
                    recordPushed(targetEnvId, c, verify);
                });
            }
            pushed.add(c);
            expected = c.getHash();
        }
        log.info("Pushed {} commit(s) {} -> {} commit by commit: HEAD {} -> {}", pushed.size(), sourceEnvId,
                targetEnvId, shortHash(start.head()), shortHash(tip));
        return new PushResult(sourceEnvId, targetEnvId, start.head(), tip,
                pushed.stream().map(CommitRecord::toNode).toList(), verify);
    }

    /** Validates a push (inside the target's history lock) and returns the commits to apply, oldest first. */
    private List<CommitRecord> commitsToPush(PushRequest request, String tip, String targetHead) {
        String sourceEnvId = request.sourceEnvironmentId();
        String targetEnvId = request.targetEnvironmentId();
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
        return commits;
    }

    private void applyCommitSql(String targetEnvId, CommitRecord c, String sql, Timeouts timeouts) {
        try {
            runner.execute(targetEnvId, TransactionalScript.of(sql).sql(), timeouts);
        } catch (DataAccessException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "push.commitFailed", shortHash(c.getHash()),
                    c.getMessage(), targetEnvId, NestedExceptionUtils.getMostSpecificCause(e).getMessage());
        }
    }

    private void requireHead(String envId, String expected) {
        String head = storage.getHead(envId).orElse(null);
        if (!Objects.equals(head, expected)) {
            throw ApiException.conflict("migration.headMovedAfterSteps", envId, shortHash(head));
        }
    }

    private void recordPushed(String targetEnvId, CommitRecord c, boolean verify) {
        if (verify) {
            verifyPushed(targetEnvId, c);
        }
        storage.insertCommit(targetEnvId, c);
        storage.setHead(targetEnvId, c.getHash());
        storage.clearAuditLog(targetEnvId);
    }

    private void verifyPushed(String targetEnvId, CommitRecord last) {
        DatabaseSnapshot lastSnapshot = parseSnapshot(last.getSchemaSnapshotJson());
        // the commit snapshot was taken in the source environment: match by name only
        SchemaDiffResult rest = diffEngine.diffAcrossEnvironments(lastSnapshot,
                SnapshotFilter.withoutDataUntrackedIn(lastSnapshot, introspector.introspect(targetEnvId)));
        if (rest.isHasChanges()) {
            throw ApiException.conflict("push.verifyFailed", targetEnvId, shortHash(last.getHash()), summary(rest.getChanges()));
        }
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
                    .map(c -> new GlobalGraph.Commit(c.hash(), c.parentHash(), c.author(), c.message(), c.description(), c.createdAt(),
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
        return diffEngine.diff(before, parseSnapshot(c.getSchemaSnapshotJson()), null, DiffMode.RECORDED);
    }

    public CommitDetails getCommit(String envId, String hash) {
        CommitRecord c = storage.requireCommit(envId, hash);
        return new CommitDetails(c.getHash(), c.getParentHash(), c.getAuthor(), c.getMessage(), c.getDescription(),
                c.getCreatedAt(),
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

    /**
     * Snapshot of the commit {@code head}, restricted to the schemas the environment tracks. The JSON of a commit never
     * changes (the hash covers it), so it is cached: a Refresh of the working tree no longer reads the - possibly
     * megabytes large - snapshot of HEAD from the database every time. The parsed snapshot is not cached, since
     * callers may modify it.
     */
    private DatabaseSnapshot headSnapshot(String envId, String head) {
        if (head == null) {
            return DatabaseSnapshot.builder().build();
        }
        String key = envId + "|" + environments.getEnvironment(envId).getJdbcUrl() + "|" + head;
        String json;
        synchronized (snapshotJsonCache) {
            json = snapshotJsonCache.get(key);
        }
        if (json == null) {
            json = storage.requireCommit(envId, head).getSchemaSnapshotJson();
            synchronized (snapshotJsonCache) {
                snapshotJsonCache.put(key, json);
            }
        }
        return SnapshotFilter.onlySchemas(parseSnapshot(json), environments.getEnvironment(envId).explicitSchemas());
    }

    private CommitRecord newCommit(String parent, String author, String message, String description, String upSql,
                                   String downSql, DatabaseSnapshot snapshot, List<AuditLogEntry> audit) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        String snapshotJson = toJson(snapshot);
        String auditJson = audit.isEmpty() ? null : toJson(audit);
        String text = isBlank(description) ? null : description.strip();
        String hash = CommitHasher.hash(parent, author, message, text, now.toString(), upSql, downSql, snapshotJson);
        return CommitRecord.builder().hash(hash).parentHash(parent).author(author).message(message).description(text)
                .createdAt(now)
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
