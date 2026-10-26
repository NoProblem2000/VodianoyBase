package com.vodianoy.pgvc.core.bundle;

import com.vodianoy.pgvc.core.repo.CommitRecord;
import com.vodianoy.pgvc.core.repo.PgvcRepository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static com.vodianoy.pgvc.core.util.Sql.dollar;
import static com.vodianoy.pgvc.core.util.Sql.literal;

/**
 * Builds a self-contained .sql file that applies a chain of commits on an isolated database through psql or
 * DBeaver. Everything runs in one transaction:
 * <ol>
 *   <li>creates the _pgvc schema if missing</li>
 *   <li>verifies that the target HEAD equals the parent of the first commit (RAISE EXCEPTION otherwise)</li>
 *   <li>executes up_sql of every commit</li>
 *   <li>records the commits with their snapshots and moves HEAD</li>
 * </ol>
 */
public final class OfflineBundleGenerator {

    public String generate(List<CommitRecord> chain, String sourceName) {
        if (chain.isEmpty()) {
            throw new IllegalArgumentException("Bundle must contain at least one commit");
        }
        CommitRecord first = chain.get(0);
        CommitRecord last = chain.get(chain.size() - 1);
        String expectedParent = first.parentHash();

        StringBuilder sb = new StringBuilder();
        sb.append("-- =====================================================================\n");
        sb.append("-- PgGitVisualizer offline migration bundle\n");
        sb.append("-- Source environment : ").append(oneLine(sourceName)).append('\n');
        sb.append("-- Generated at       : ").append(OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC)).append('\n');
        sb.append("-- Commits            : ").append(chain.size()).append('\n');
        sb.append("-- Required HEAD      : ").append(expectedParent == null ? "<empty history>" : expectedParent).append('\n');
        sb.append("-- Resulting HEAD     : ").append(last.hash()).append('\n');
        for (CommitRecord r : chain) {
            sb.append("--   ").append(r.hash(), 0, 12).append("  ").append(oneLine(r.message())).append('\n');
        }
        sb.append("--\n");
        sb.append("-- Run as a superuser, e.g.:  psql -v ON_ERROR_STOP=1 -d <database> -f <this file>\n");
        sb.append("-- The whole bundle is atomic: on any error nothing is applied.\n");
        sb.append("-- =====================================================================\n\n");

        sb.append("BEGIN;\n\n");
        sb.append("-- ---------------------------------------------------------------------\n");
        sb.append("-- 1. Version-control metadata schema (_pgvc)\n");
        sb.append("-- ---------------------------------------------------------------------\n");
        sb.append(PgvcRepository.INSTALL_SQL.strip()).append("\n\n");
        sb.append("LOCK TABLE _pgvc.refs IN EXCLUSIVE MODE;\n\n");

        sb.append("-- ---------------------------------------------------------------------\n");
        sb.append("-- 2. HEAD check (protection against out-of-sync targets)\n");
        sb.append("-- ---------------------------------------------------------------------\n");
        sb.append("DO $pgvc_check$\n");
        sb.append("DECLARE\n");
        sb.append("    v_head VARCHAR(64);\n");
        sb.append("BEGIN\n");
        sb.append("    SELECT commit_hash INTO v_head FROM _pgvc.refs WHERE ref_name = 'HEAD';\n");
        sb.append("    IF v_head IS NOT DISTINCT FROM ").append(literal(last.hash())).append(" THEN\n");
        sb.append("        RAISE EXCEPTION 'PgGitVisualizer: this bundle is already applied (HEAD = %).', v_head;\n");
        sb.append("    END IF;\n");
        sb.append("    IF v_head IS DISTINCT FROM ").append(literal(expectedParent)).append(" THEN\n");
        sb.append("        RAISE EXCEPTION 'PgGitVisualizer: HEAD mismatch. The bundle requires HEAD = %, but the database is at %. Nothing was applied.',\n");
        sb.append("            ").append(literal(expectedParent == null ? "<empty history>" : expectedParent))
                .append(", coalesce(v_head, '<empty history>');\n");
        sb.append("    END IF;\n");
        sb.append("END;\n");
        sb.append("$pgvc_check$;\n\n");

        sb.append("-- ---------------------------------------------------------------------\n");
        sb.append("-- 3. Migrations\n");
        sb.append("-- ---------------------------------------------------------------------\n");
        int i = 0;
        for (CommitRecord r : chain) {
            i++;
            sb.append("\n-- >>> Commit ").append(i).append('/').append(chain.size()).append(": ").append(r.hash()).append('\n');
            sb.append("-- >>> ").append(oneLine(r.message())).append("  (").append(oneLine(r.author())).append(", ")
                    .append(r.createdAt()).append(")\n");
            sb.append(r.upSql().strip()).append("\n;\n\n");
            sb.append("INSERT INTO _pgvc.commits (hash, parent_hash, author, message, created_at, up_sql, down_sql,"
                    + " schema_snapshot, ddl_log_history)\nVALUES (")
                    .append(literal(r.hash())).append(", ")
                    .append(literal(r.parentHash())).append(", ")
                    .append(literal(r.author())).append(",\n        ")
                    .append(dollar(r.message())).append(",\n        ")
                    .append(literal(r.createdAt().toString())).append("::timestamptz,\n        ")
                    .append(dollar(r.upSql())).append(",\n        ")
                    .append(dollar(r.downSql())).append(",\n        ")
                    .append(dollar(r.snapshotJson())).append("::jsonb,\n        ")
                    .append(r.ddlLogHistoryJson() == null ? "NULL" : dollar(r.ddlLogHistoryJson()) + "::jsonb")
                    .append(")\nON CONFLICT (hash) DO NOTHING;\n");
        }

        sb.append("\n-- ---------------------------------------------------------------------\n");
        sb.append("-- 4. Move HEAD\n");
        sb.append("-- ---------------------------------------------------------------------\n");
        sb.append("INSERT INTO _pgvc.refs (ref_name, commit_hash) VALUES ('HEAD', ").append(literal(last.hash()))
                .append(")\nON CONFLICT (ref_name) DO UPDATE SET commit_hash = EXCLUDED.commit_hash;\n\n");
        sb.append("-- DDL executed by this bundle is part of the commits above\n");
        sb.append("DELETE FROM _pgvc.ddl_audit_log;\n\n");
        sb.append("COMMIT;\n");
        return sb.toString();
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("[\\r\\n]+", " ");
    }
}
