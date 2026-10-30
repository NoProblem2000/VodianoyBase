package com.vodianoy.pgvc.snapshot;

import com.vodianoy.pgvc.common.ApiException;
import com.vodianoy.pgvc.diff.ChangeItem;
import com.vodianoy.pgvc.diff.SchemaDiffEngine;
import com.vodianoy.pgvc.diff.SchemaDiffResult;
import com.vodianoy.pgvc.environment.Environment;
import com.vodianoy.pgvc.environment.EnvironmentManagerService;
import com.vodianoy.pgvc.introspection.PostgresSchemaIntrospector;
import com.vodianoy.pgvc.model.schema.DatabaseSnapshot;
import com.vodianoy.pgvc.storage.PgVcStatus;
import com.vodianoy.pgvc.storage.PgVcStorageService;
import com.vodianoy.pgvc.vcs.CommitDetails;
import com.vodianoy.pgvc.vcs.CommitResult;
import com.vodianoy.pgvc.vcs.ExecuteMigrationRequest;
import com.vodianoy.pgvc.vcs.VersionControlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

/**
 * Reference snapshots for isolated sites:
 * <ol>
 *   <li>{@link #export}: the schema of a commit (or HEAD, or the live database) as a {@code .pgsnap.json} file;</li>
 *   <li>{@link #compare}: the live database of the site vs the uploaded snapshot, matched by object names only
 *       (OIDs of another database mean nothing);</li>
 *   <li>{@link #apply}: the reviewed SQL runs in one transaction and is recorded as a commit of the site.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SnapshotSyncService {

    private final EnvironmentManagerService environments;
    private final PgVcStorageService storage;
    private final PostgresSchemaIntrospector introspector;
    private final SchemaDiffEngine diffEngine;
    private final VersionControlService versionControl;

    /**
     * @param commitHash blank: HEAD of the environment, or its live schema when it has no commits yet
     */
    public SnapshotExport export(String envId, String commitHash) {
        Environment env = requireEnvironment(envId);
        PgVcStatus status = requireReachable(envId);
        String hash = isBlank(commitHash) ? (status.initialized() ? status.head() : null) : commitHash.strip();

        SnapshotBundleDto.SnapshotBundleDtoBuilder bundle = SnapshotBundleDto.builder()
                .exportedAt(Instant.now().truncatedTo(ChronoUnit.MILLIS))
                .sourceEnvironmentName(env.getName())
                .sourceBranch(env.getId());
        if (hash != null) {
            CommitDetails commit = versionControl.getCommit(envId, hash);
            bundle.sourceCommitHash(commit.hash()).sourceCommitMessage(commit.message())
                    .schemaSnapshot(commit.schemaSnapshot());
        } else {
            bundle.schemaSnapshot(introspector.introspect(envId));
        }
        String fileName = "snapshot_" + fileSafe(env.getId()) + "_" + (hash == null ? "live" : hash.substring(0, 7))
                + SnapshotBundleDto.FILE_EXTENSION;
        return new SnapshotExport(fileName, bundle.build());
    }

    /** Live schema of {@code envId} (the current state) -> uploaded snapshot (the target state). */
    public SnapshotComparisonResponse compare(SnapshotCompareRequest request) {
        String envId = request == null ? null : request.envId();
        Environment env = requireEnvironment(envId);
        SnapshotBundleDto bundle = validate(request.bundle());
        PgVcStatus status = requireReachable(envId);

        DatabaseSnapshot current = introspector.introspect(envId);
        SchemaDiffResult diff = diffEngine.compare(current, bundle.getSchemaSnapshot(), true);

        List<ChangeItem> missing = diff.getChanges().stream().filter(c -> !c.isExtraInTargetDb()).toList();
        List<ChangeItem> destructive = diff.getChanges().stream().filter(ChangeItem::isExtraInTargetDb).toList();
        List<Integer> defaults = missing.stream().map(ChangeItem::getId).toList();

        String head = status.initialized() ? status.head() : null;
        boolean dirty;
        if (head != null) {
            DatabaseSnapshot headSnapshot = versionControl.getCommit(envId, head).schemaSnapshot();
            dirty = diffEngine.diff(headSnapshot, current).isHasChanges();
        } else {
            dirty = !current.isEmpty();
        }
        return new SnapshotComparisonResponse(envId, env.getName(), head, SnapshotSource.of(bundle),
                !diff.isHasChanges(), missing, destructive, diff.getUpStatements(), diff.getDownStatements(),
                SchemaDiffEngine.assemble(diff.getUpStatements(), defaults),
                SchemaDiffEngine.assemble(diff.getDownStatements(), defaults),
                diff.getWarnings(), dirty, current.getServerVersion());
    }

    /**
     * Executes the reviewed UP SQL on {@code envId} in one transaction, takes the resulting schema and records it as a
     * new commit there (HEAD moves, the DDL audit log is cleared); any error rolls everything back. The database may
     * have changes that are not committed yet (e.g. a fresh installation without history): the commit records the
     * actual resulting schema. _pgvc is installed first when missing.
     */
    public CommitResult apply(SnapshotApplyRequest request) {
        String envId = request == null ? null : request.envId();
        requireEnvironment(envId);
        if (!requireReachable(envId).initialized()) {
            storage.initializeEnvironment(envId);
        }
        String message = isBlank(request.message())
                ? "Sync with external snapshot [" + shortHash(request.sourceCommitHash()) + "]"
                : request.message().strip();
        CommitResult result = versionControl.executeAndCommitSql(envId,
                new ExecuteMigrationRequest(message, request.author(), request.upSql(), request.downSql(), true));
        log.info("Applied external snapshot {} to {}: commit {}", shortHash(request.sourceCommitHash()), envId,
                shortHash(result.commit().hash()));
        return result;
    }

    // ------------------------------------------------------------------ helpers

    private static SnapshotBundleDto validate(SnapshotBundleDto bundle) {
        if (bundle == null || bundle.getSchemaSnapshot() == null) {
            throw ApiException.badRequest("snapshot.missingSchema");
        }
        String version = bundle.getFormatVersion();
        if (version == null || !(version.equals("1") || version.startsWith("1."))) {
            throw ApiException.badRequest("snapshot.unsupportedFormat", String.valueOf(version),
                    SnapshotBundleDto.FORMAT_VERSION);
        }
        return bundle;
    }

    private Environment requireEnvironment(String envId) {
        if (isBlank(envId)) {
            throw ApiException.badRequest("env.idRequired");
        }
        return environments.getEnvironment(envId);
    }

    private PgVcStatus requireReachable(String envId) {
        PgVcStatus status = storage.getStatus(envId);
        if (!status.reachable()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "env.unreachable", envId, status.error());
        }
        return status;
    }

    /** Letters, digits, '-' and '_' (any alphabet); everything else becomes '-'. */
    static String fileSafe(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
            sb.append(Character.isLetterOrDigit(c) || c == '-' || c == '_' ? c : '-');
        }
        return sb.toString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String shortHash(String hash) {
        return hash == null || hash.isBlank() ? "live" : hash.substring(0, Math.min(7, hash.length()));
    }
}
