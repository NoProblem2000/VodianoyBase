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
            rs.getObject("created_at", OffsetDateTime.class));

    private static final RowMapper<AuditLogEntry> AUDIT_ENTRY = (rs, n) -> new AuditLogEntry(
            rs.getLong("id"), rs.getString("event_type"), rs.getString("command_tag"), rs.getString("object_type"),
            rs.getString("object_identity"), rs.getString("raw_query"), rs.getString("db_user"),
            rs.getObject("executed_at", OffsetDateTime.class));

    private final EnvironmentManagerService environments;

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
                throw ApiException.conflict("PostgreSQL 11 or newer is required");
            }
            if (!Boolean.TRUE.equals(info.get("superuser"))) {
                throw ApiException.conflict("The user of environment '" + envId + "' is not a SUPERUSER:"
                        + " event triggers can only be created by a superuser");
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
        List<CommitNode> commits = jdbc.query("SELECT hash, parent_hash, author, message, created_at"
                + " FROM _pgvc.commits ORDER BY created_at DESC, hash", COMMIT_NODE);
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

    /** Deletes audit entries up to and including {@code upToId} (entries logged later are kept). */
    public int clearAuditLog(String envId, long upToId) {
        return requireInitialized(envId).update("DELETE FROM _pgvc.ddl_audit_log WHERE id <= ?", upToId);
    }

    // ------------------------------------------------------------------ helpers

    private JdbcTemplate requireInitialized(String envId) {
        JdbcTemplate jdbc = environments.getJdbcTemplate(envId);
        Boolean installed = jdbc.queryForObject("SELECT to_regclass('_pgvc.commits') IS NOT NULL"
                + " AND to_regclass('_pgvc.refs') IS NOT NULL AND to_regclass('_pgvc.ddl_audit_log') IS NOT NULL",
                Boolean.class);
        if (!Boolean.TRUE.equals(installed)) {
            throw ApiException.conflict("Version control is not initialized in environment '" + envId
                    + "'. Call POST /api/environments/" + envId + "/pgvc/initialize first.");
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
