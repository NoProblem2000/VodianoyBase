package com.vodianoy.pgvc.storage;

import com.vodianoy.pgvc.common.ApiException;
import com.vodianoy.pgvc.environment.EnvironmentManagerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Access to the version-control metadata (schema _pgvc) inside a target database: installation of the schema and
 * the DDL event triggers, the commit graph, HEAD and the DDL audit log.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PgVcStorageService {

    public static final String HEAD_REF = "HEAD";
    static final String INSTALL_SCRIPT = readResource("db/pgvc-install.sql");

    private static final RowMapper<CommitNode> COMMIT_NODE = (rs, n) -> new CommitNode(
            rs.getString("hash"), rs.getString("parent_hash"), rs.getString("author"), rs.getString("message"),
            rs.getString("description"),
            rs.getObject("created_at", OffsetDateTime.class));

    private static final RowMapper<AuditLogEntry> AUDIT_ENTRY = (rs, n) -> new AuditLogEntry(
            rs.getLong("id"), rs.getString("event_type"), rs.getString("command_tag"), rs.getString("object_type"),
            rs.getString("object_identity"), rs.getString("raw_query"), rs.getString("db_user"),
            rs.getObject("executed_at", OffsetDateTime.class));

    private final EnvironmentManagerService environments;

    /** Upgrade of installations older than v1.9.0 (the same block as in the install script). */
    static final String DESCRIPTION_DDL = """
            DO $pgvc_upgrade$
            BEGIN
                ALTER TABLE _pgvc.commits ADD COLUMN IF NOT EXISTS description TEXT NULL;
            EXCEPTION WHEN insufficient_privilege THEN
                RAISE NOTICE 'pgvc: column _pgvc.commits.description not added: %', SQLERRM;
            END
            $pgvc_upgrade$""";

    /** Databases (environment + JDBC URL) whose _pgvc.commits has the description column. */
    private final Set<String> withDescription = ConcurrentHashMap.newKeySet();

    /**
     * Creates schema _pgvc with its tables, the PL/pgSQL functions {@code _pgvc.fn_log_ddl_end()} /
     * {@code _pgvc.fn_log_sql_drop()} and the event triggers {@code pgvc_ddl_trigger} (ddl_command_end) /
     * {@code pgvc_drop_trigger} (sql_drop). Idempotent; runs in one transaction; requires a SUPERUSER.
     */
    public PgVcStatus initializeEnvironment(String envId) {
        JdbcTemplate jdbc = environments.getJdbcTemplate(envId);
        environments.getTransactionTemplate(envId).executeWithoutResult(tx -> {
            Map<String, Object> info = jdbc.queryForMap("SELECT current_setting('server_version_num')::int AS version,"
                    + " (SELECT rolsuper FROM pg_catalog.pg_roles WHERE rolname = current_user) AS superuser");
            if (((Number) info.get("version")).intValue() < 110000) {
                throw ApiException.conflict("pgvc.pgVersion");
            }
            if (!Boolean.TRUE.equals(info.get("superuser"))) {
                throw ApiException.conflict("pgvc.notSuperuser", envId);
            }
            jdbc.execute(INSTALL_SCRIPT);
        });
        log.info("Version control initialized in environment {}", envId);
        return getStatus(envId);
    }

    /** Never fails because the database is down: returns reachable = false with the error instead. */
    public PgVcStatus getStatus(String envId) {
        JdbcTemplate jdbc = environments.getJdbcTemplate(envId);
        try {
            Map<String, Object> s = jdbc.queryForMap("""
                    SELECT to_regclass('_pgvc.commits') IS NOT NULL
                           AND to_regclass('_pgvc.refs') IS NOT NULL
                           AND to_regclass('_pgvc.ddl_audit_log') IS NOT NULL               AS tables_ok,
                           to_regprocedure('_pgvc.fn_log_ddl_end()') IS NOT NULL
                           AND to_regprocedure('_pgvc.fn_log_sql_drop()') IS NOT NULL       AS functions_ok,
                           EXISTS (SELECT 1 FROM pg_catalog.pg_event_trigger
                                   WHERE evtname = 'pgvc_ddl_trigger' AND evtenabled <> 'D')  AS ddl_trigger,
                           EXISTS (SELECT 1 FROM pg_catalog.pg_event_trigger
                                   WHERE evtname = 'pgvc_drop_trigger' AND evtenabled <> 'D') AS drop_trigger,
                           (SELECT rolsuper FROM pg_catalog.pg_roles WHERE rolname = current_user) AS superuser
                    """);
            boolean tables = Boolean.TRUE.equals(s.get("tables_ok"));
            boolean initialized = tables && Boolean.TRUE.equals(s.get("functions_ok"));
            String head = null;
            long commits = 0;
            long pending = 0;
            if (tables) {
                head = readHead(jdbc).orElse(null);
                commits = count(jdbc, "SELECT count(*) FROM _pgvc.commits");
                pending = count(jdbc, "SELECT count(*) FROM _pgvc.ddl_audit_log");
            }
            return new PgVcStatus(envId, true, initialized, Boolean.TRUE.equals(s.get("ddl_trigger")),
                    Boolean.TRUE.equals(s.get("drop_trigger")), Boolean.TRUE.equals(s.get("superuser")),
                    head, commits, pending, null);
        } catch (CannotGetJdbcConnectionException e) {
            String message = NestedExceptionUtils.getMostSpecificCause(e).getMessage();
            return new PgVcStatus(envId, false, false, false, false, false, null, 0, 0, message);
        }
    }

    /** All commits (newest first) and the current HEAD. */
    public CommitGraph getCommitGraph(String envId) {
        JdbcTemplate jdbc = requireInitialized(envId);
        List<CommitNode> commits = jdbc.query("SELECT hash, parent_hash, author, message, "
                + descriptionColumn(envId, jdbc) + ", created_at FROM _pgvc.commits ORDER BY created_at DESC, hash",
                COMMIT_NODE);
        return new CommitGraph(envId, readHead(jdbc).orElse(null), commits);
    }

    public Optional<String> getHead(String envId) {
        return readHead(requireInitialized(envId));
    }

    /** DDL captured since the last commit, oldest first. */
    public List<AuditLogEntry> readAuditLog(String envId) {
        return requireInitialized(envId).query("SELECT id, event_type, command_tag, object_type, object_identity,"
                + " raw_query, db_user, executed_at FROM _pgvc.ddl_audit_log ORDER BY id", AUDIT_ENTRY);
    }

    /** Deletes the whole audit log. Returns the number of deleted entries. */
    public int clearAuditLog(String envId) {
        return requireInitialized(envId).update("DELETE FROM _pgvc.ddl_audit_log");
    }

    /** Highest id in the audit log, 0 when it is empty. */
    public long maxAuditLogId(String envId) {
        Long id = requireInitialized(envId).queryForObject("SELECT COALESCE(max(id), 0) FROM _pgvc.ddl_audit_log", Long.class);
        return id == null ? 0 : id;
    }

    /** Deletes audit entries logged after {@code afterId} (e.g. the DDL a discard executed itself). */
    public int clearAuditLogAfter(String envId, long afterId) {
        return requireInitialized(envId).update("DELETE FROM _pgvc.ddl_audit_log WHERE id > ?", afterId);
    }

    /** Deletes audit entries up to and including {@code upToId} (entries logged later are kept). */
    public int clearAuditLog(String envId, long upToId) {
        return requireInitialized(envId).update("DELETE FROM _pgvc.ddl_audit_log WHERE id <= ?", upToId);
    }

    // ------------------------------------------------------------------ commits (used inside transactions)

    private static final RowMapper<CommitRecord> COMMIT_RECORD = (rs, n) -> CommitRecord.builder()
            .hash(rs.getString("hash"))
            .parentHash(rs.getString("parent_hash"))
            .author(rs.getString("author"))
            .message(rs.getString("message"))
            .description(rs.getString("description"))
            .createdAt(rs.getObject("created_at", OffsetDateTime.class))
            .upSql(rs.getString("up_sql"))
            .downSql(rs.getString("down_sql"))
            .schemaSnapshotJson(rs.getString("schema_snapshot"))
            .ddlLogHistoryJson(rs.getString("ddl_log_history"))
            .build();

    /**
     * Serializes writers of the version history of one database until the end of the current transaction
     * (pg_advisory_xact_lock). Call it inside a transaction of the environment.
     */
    public void lockHistory(String envId) {
        requireInitialized(envId).execute("SELECT pg_advisory_xact_lock(hashtext('_pgvc.history'))");
    }

    public Optional<CommitRecord> findCommit(String envId, String hash) {
        JdbcTemplate jdbc = requireInitialized(envId);
        List<CommitRecord> rows = jdbc.query("SELECT hash, parent_hash, author, message, " + descriptionColumn(envId, jdbc)
                + ", created_at, up_sql, down_sql, schema_snapshot::text AS schema_snapshot,"
                + " ddl_log_history::text AS ddl_log_history FROM _pgvc.commits WHERE hash = ?", COMMIT_RECORD, hash);
        return rows.stream().findFirst();
    }

    public CommitRecord requireCommit(String envId, String hash) {
        return findCommit(envId, hash).orElseThrow(() ->
                ApiException.notFound("commit.notFound", hash, envId));
    }

    /**
     * Inserts a commit; returns false when a commit with this hash already exists. A commit with a description on an
     * installation older than v1.9.0 first adds the column; when the role may not (it does not own _pgvc.commits),
     * the commit is refused with a hint to initialize version control again as a superuser.
     */
    public boolean insertCommit(String envId, CommitRecord c) {
        JdbcTemplate jdbc = requireInitialized(envId);
        boolean described = c.getDescription() != null && !c.getDescription().isBlank();
        if (described && !hasDescription(envId, jdbc)) {
            jdbc.execute(DESCRIPTION_DDL);
            if (!hasDescription(envId, jdbc)) {
                throw ApiException.conflict("pgvc.descriptionUpgrade", envId);
            }
        }
        if (described) {
            return jdbc.update("INSERT INTO _pgvc.commits (hash, parent_hash, author, message, description, created_at,"
                            + " up_sql, down_sql, schema_snapshot, ddl_log_history)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb) ON CONFLICT (hash) DO NOTHING",
                    c.getHash(), c.getParentHash(), c.getAuthor(), c.getMessage(), c.getDescription(), c.getCreatedAt(),
                    c.getUpSql(), c.getDownSql(), c.getSchemaSnapshotJson(), c.getDdlLogHistoryJson()) == 1;
        }
        return jdbc.update("INSERT INTO _pgvc.commits (hash, parent_hash, author, message, created_at,"
                        + " up_sql, down_sql, schema_snapshot, ddl_log_history)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb) ON CONFLICT (hash) DO NOTHING",
                c.getHash(), c.getParentHash(), c.getAuthor(), c.getMessage(), c.getCreatedAt(), c.getUpSql(),
                c.getDownSql(), c.getSchemaSnapshotJson(), c.getDdlLogHistoryJson()) == 1;
    }

    /** _pgvc.commits has the description column (v1.9.0+; positive answers are remembered per database). */
    private boolean hasDescription(String envId, JdbcTemplate jdbc) {
        String key = envId + "|" + environments.getEnvironment(envId).getJdbcUrl();
        if (withDescription.contains(key)) {
            return true;
        }
        Boolean has = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_attribute"
                + " WHERE attrelid = to_regclass('_pgvc.commits') AND attname = 'description' AND NOT attisdropped)",
                Boolean.class);
        if (Boolean.TRUE.equals(has)) {
            withDescription.add(key);
            return true;
        }
        return false;
    }

    /** {@code description}, or NULL on installations older than v1.9.0. */
    private String descriptionColumn(String envId, JdbcTemplate jdbc) {
        return hasDescription(envId, jdbc) ? "description" : "NULL::text AS description";
    }

    /**
     * Deletes every commit whose hash is not in {@code keep} (one statement, so parent links between deleted commits
     * do not matter). HEAD must already point at a kept commit. Returns the number of deleted commits.
     */
    public int deleteCommitsExcept(String envId, java.util.Collection<String> keep) {
        return requireInitialized(envId).update("DELETE FROM _pgvc.commits WHERE NOT (hash = ANY (?::varchar[]))",
                (Object) keep.toArray(String[]::new));
    }

    /** Deletes the given commits (one statement: parent links between them do not matter). Returns the count. */
    public int deleteCommits(String envId, java.util.Collection<String> hashes) {
        return requireInitialized(envId).update("DELETE FROM _pgvc.commits WHERE hash = ANY (?::varchar[])",
                (Object) hashes.toArray(String[]::new));
    }

    /** Points HEAD at the given commit. */
    public void setHead(String envId, String hash) {
        requireInitialized(envId).update("INSERT INTO _pgvc.refs (ref_name, commit_hash) VALUES (?, ?)"
                + " ON CONFLICT (ref_name) DO UPDATE SET commit_hash = EXCLUDED.commit_hash", HEAD_REF, hash);
    }

    // ------------------------------------------------------------------ reference data tables

    private static final String TRACKED_TABLE_DDL = """
            CREATE TABLE IF NOT EXISTS _pgvc.tracked_data_tables (
                schema_name VARCHAR(200) NOT NULL,
                table_name  VARCHAR(200) NOT NULL,
                tracked_by  VARCHAR(100) NULL,
                tracked_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
                PRIMARY KEY (schema_name, table_name)
            )""";

    /**
     * Tables tracked as reference data. Empty when _pgvc or the table _pgvc.tracked_data_tables (added in v1.7.0)
     * does not exist; never fails because of that.
     */
    public List<TrackedDataTable> listTrackedTables(String envId) {
        JdbcTemplate jdbc = environments.getJdbcTemplate(envId);
        Boolean exists = jdbc.queryForObject("SELECT to_regclass('_pgvc.tracked_data_tables') IS NOT NULL", Boolean.class);
        if (!Boolean.TRUE.equals(exists)) {
            return List.of();
        }
        return jdbc.query("SELECT schema_name, table_name, tracked_by, tracked_at FROM _pgvc.tracked_data_tables"
                        + " ORDER BY schema_name, table_name",
                (rs, n) -> new TrackedDataTable(rs.getString("schema_name"), rs.getString("table_name"),
                        rs.getString("tracked_by"), rs.getObject("tracked_at", OffsetDateTime.class)));
    }

    /** Marks a table as reference data (creates _pgvc.tracked_data_tables on installations older than v1.7.0). */
    public void trackTable(String envId, String schema, String table, String trackedBy) {
        JdbcTemplate jdbc = requireInitialized(envId);
        jdbc.execute(TRACKED_TABLE_DDL);
        jdbc.update("INSERT INTO _pgvc.tracked_data_tables (schema_name, table_name, tracked_by) VALUES (?, ?, ?)"
                + " ON CONFLICT (schema_name, table_name) DO NOTHING", schema, table, trackedBy);
    }

    /** Returns false when the table was not tracked. */
    public boolean untrackTable(String envId, String schema, String table) {
        JdbcTemplate jdbc = requireInitialized(envId);
        Boolean exists = jdbc.queryForObject("SELECT to_regclass('_pgvc.tracked_data_tables') IS NOT NULL", Boolean.class);
        return Boolean.TRUE.equals(exists) && jdbc.update(
                "DELETE FROM _pgvc.tracked_data_tables WHERE schema_name = ? AND table_name = ?", schema, table) == 1;
    }

    // ------------------------------------------------------------------ helpers

    private JdbcTemplate requireInitialized(String envId) {
        JdbcTemplate jdbc = environments.getJdbcTemplate(envId);
        Boolean installed = jdbc.queryForObject("SELECT to_regclass('_pgvc.commits') IS NOT NULL"
                + " AND to_regclass('_pgvc.refs') IS NOT NULL AND to_regclass('_pgvc.ddl_audit_log') IS NOT NULL",
                Boolean.class);
        if (!Boolean.TRUE.equals(installed)) {
            throw ApiException.conflict("pgvc.notInitialized", envId);
        }
        return jdbc;
    }

    private static Optional<String> readHead(JdbcTemplate jdbc) {
        List<String> head = jdbc.queryForList("SELECT commit_hash FROM _pgvc.refs WHERE ref_name = ?",
                String.class, HEAD_REF);
        return head.isEmpty() ? Optional.empty() : Optional.ofNullable(head.get(0));
    }

    private static long count(JdbcTemplate jdbc, String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }

    private static String readResource(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Missing classpath resource " + path, e);
        }
    }
}
