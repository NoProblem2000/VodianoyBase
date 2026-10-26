package com.vodianoy.pgvc.core.repo;

import com.vodianoy.pgvc.core.util.Json;
import com.vodianoy.pgvc.core.util.Resources;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Data access for the _pgvc metadata schema of one target database (one JDBC connection). */
public final class PgvcRepository {

    public static final String INSTALL_SQL = Resources.text("sql/install.sql");
    public static final String HEAD = "HEAD";

    private final Connection c;

    public PgvcRepository(Connection connection) {
        this.c = connection;
    }

    public boolean isInstalled() throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT to_regclass('_pgvc.commits') IS NOT NULL"
                     + " AND to_regclass('_pgvc.refs') IS NOT NULL AND to_regclass('_pgvc.ddl_audit_log') IS NOT NULL")) {
            rs.next();
            return rs.getBoolean(1);
        }
    }

    public boolean eventTriggersInstalled() throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM pg_catalog.pg_event_trigger"
                     + " WHERE evtname IN ('pgvc_ddl_command_end', 'pgvc_sql_drop') AND evtenabled <> 'D'")) {
            rs.next();
            return rs.getInt(1) == 2;
        }
    }

    public void install() throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute(INSTALL_SQL);
        }
    }

    /** Serializes concurrent writers of the version history (transaction-scoped). */
    public void lock() throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("SELECT pg_advisory_xact_lock(hashtext('_pgvc.history'))");
        }
    }

    public String head() throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT commit_hash FROM _pgvc.refs WHERE ref_name = ?")) {
            ps.setString(1, HEAD);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    public void setHead(String hash) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO _pgvc.refs (ref_name, commit_hash) VALUES (?, ?)"
                + " ON CONFLICT (ref_name) DO UPDATE SET commit_hash = EXCLUDED.commit_hash")) {
            ps.setString(1, HEAD);
            ps.setString(2, hash);
            ps.executeUpdate();
        }
    }

    public List<CommitSummary> listCommits() throws SQLException {
        List<CommitSummary> out = new ArrayList<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT hash, parent_hash, author, message, created_at"
                     + " FROM _pgvc.commits ORDER BY created_at DESC, hash")) {
            while (rs.next()) {
                out.add(new CommitSummary(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        toInstant(rs.getTimestamp(5)).toString()));
            }
        }
        return out;
    }

    public CommitRecord findCommit(String hash) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT hash, parent_hash, author, message, created_at, up_sql,"
                + " down_sql, schema_snapshot::text, ddl_log_history::text FROM _pgvc.commits WHERE hash = ?")) {
            ps.setString(1, hash);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new CommitRecord(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        toInstant(rs.getTimestamp(5)), rs.getString(6), rs.getString(7), rs.getString(8),
                        rs.getString(9));
            }
        }
    }

    public boolean insertCommit(CommitRecord r) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO _pgvc.commits (hash, parent_hash, author, message,"
                + " created_at, up_sql, down_sql, schema_snapshot, ddl_log_history)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb) ON CONFLICT (hash) DO NOTHING")) {
            ps.setString(1, r.hash());
            ps.setString(2, r.parentHash());
            ps.setString(3, r.author());
            ps.setString(4, r.message());
            ps.setObject(5, OffsetDateTime.ofInstant(r.createdAt(), ZoneOffset.UTC));
            ps.setString(6, r.upSql());
            ps.setString(7, r.downSql());
            ps.setString(8, r.snapshotJson());
            ps.setString(9, r.ddlLogHistoryJson());
            return ps.executeUpdate() == 1;
        }
    }

    public List<AuditEntry> auditLog() throws SQLException {
        List<AuditEntry> out = new ArrayList<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, event_type, command_tag, object_type, object_identity,"
                     + " raw_query, db_user, executed_at FROM _pgvc.ddl_audit_log ORDER BY id")) {
            while (rs.next()) {
                Timestamp ts = rs.getTimestamp(8);
                out.add(new AuditEntry(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), ts == null ? null : toInstant(ts).toString()));
            }
        }
        return out;
    }

    public void clearAuditLog(long upToIdInclusive) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM _pgvc.ddl_audit_log WHERE id <= ?")) {
            ps.setLong(1, upToIdInclusive);
            ps.executeUpdate();
        }
    }

    public void clearAuditLog() throws SQLException {
        try (Statement st = c.createStatement()) {
            st.executeUpdate("DELETE FROM _pgvc.ddl_audit_log");
        }
    }

    /** JSON array of audit entries for _pgvc.commits.ddl_log_history (null when empty). */
    public static String auditHistoryJson(List<AuditEntry> entries) {
        if (entries.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AuditEntry e : entries) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id());
            m.put("event_type", e.eventType());
            m.put("command_tag", e.commandTag());
            m.put("object_type", e.objectType());
            m.put("object_identity", e.objectIdentity());
            m.put("raw_query", e.rawQuery());
            m.put("db_user", e.dbUser());
            m.put("executed_at", e.executedAt());
            rows.add(m);
        }
        return Json.write(rows);
    }

    private static Instant toInstant(Timestamp ts) {
        return ts.toInstant();
    }
}
