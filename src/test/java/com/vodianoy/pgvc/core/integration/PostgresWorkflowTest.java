package com.vodianoy.pgvc.core.integration;

import com.vodianoy.pgvc.core.diff.DiffEngine;
import com.vodianoy.pgvc.core.diff.DiffResult;
import com.vodianoy.pgvc.core.env.ConnectionFactory;
import com.vodianoy.pgvc.core.env.EnvironmentConfig;
import com.vodianoy.pgvc.core.env.EnvironmentStore;
import com.vodianoy.pgvc.core.introspect.Introspector;
import com.vodianoy.pgvc.core.model.DatabaseSnapshot;
import com.vodianoy.pgvc.core.service.Dtos.Bundle;
import com.vodianoy.pgvc.core.service.Dtos.CommitDetails;
import com.vodianoy.pgvc.core.service.Dtos.CommitRequest;
import com.vodianoy.pgvc.core.service.Dtos.CommitResult;
import com.vodianoy.pgvc.core.service.Dtos.ExecuteRequest;
import com.vodianoy.pgvc.core.service.Dtos.Graph;
import com.vodianoy.pgvc.core.service.Dtos.PushRequest;
import com.vodianoy.pgvc.core.service.Dtos.PushResult;
import com.vodianoy.pgvc.core.service.Dtos.WorkingTree;
import com.vodianoy.pgvc.core.service.PgvcService;
import com.vodianoy.pgvc.core.util.PgvcException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end workflow against a real PostgreSQL server (11+). Enabled when PGVC_TEST_JDBC_URL is set, e.g.
 * {@code PGVC_TEST_JDBC_URL=jdbc:postgresql://localhost:5432/postgres PGVC_TEST_USER=postgres PGVC_TEST_PASSWORD=postgres}.
 * The test creates and drops its own databases (pgvc_it_*), so the user must be a superuser.
 */
@EnabledIfEnvironmentVariable(named = "PGVC_TEST_JDBC_URL", matches = ".+")
class PostgresWorkflowTest {

    private static final String BASE_URL = System.getenv("PGVC_TEST_JDBC_URL");
    private static final String USER = envOr("PGVC_TEST_USER", "postgres");
    private static final String PASSWORD = envOr("PGVC_TEST_PASSWORD", "");

    private static String envOr(String name, String def) {
        String v = System.getenv(name);
        return v == null ? def : v;
    }

    private static String url(String db) {
        return BASE_URL.substring(0, BASE_URL.lastIndexOf('/') + 1) + db;
    }

    private static void exec(String db, String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(url(db), USER, PASSWORD); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private static String query(String db, String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(url(db), USER, PASSWORD); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static void recreate(String db) throws SQLException {
        String admin = BASE_URL.substring(BASE_URL.lastIndexOf('/') + 1);
        exec(admin, "DROP DATABASE IF EXISTS " + db);
        exec(admin, "CREATE DATABASE " + db);
    }

    private static void assertOrdered(String sql, String... parts) {
        int last = -1;
        for (String p : parts) {
            int idx = sql.indexOf(p);
            assertTrue(idx >= 0, "missing: " + p + "\n" + sql);
            assertTrue(idx > last, "wrong order at: " + p + "\n" + sql);
            last = idx;
        }
    }

    @Test
    void fullWorkflow() throws Exception {
        for (String db : List.of("pgvc_it_dev", "pgvc_it_prod", "pgvc_it_offline")) {
            recreate(db);
        }
        Path file = Files.createTempFile("pgvc-envs", ".json");
        Files.delete(file);
        EnvironmentStore store = new EnvironmentStore(file);
        store.save(new EnvironmentConfig(null, "Dev", null, url("pgvc_it_dev"), USER, PASSWORD));
        store.save(new EnvironmentConfig(null, "Prod", null, url("pgvc_it_prod"), USER, PASSWORD));
        store.save(new EnvironmentConfig(null, "Offline", null, url("pgvc_it_offline"), USER, PASSWORD));
        PgvcService svc = new PgvcService(store, new ConnectionFactory(5));
        DiffEngine diff = new DiffEngine();

        // ---- install
        assertFalse(svc.status("dev").installed());
        assertTrue(svc.install("dev").triggersInstalled());

        // ---- Mode A: changes made in an external IDE
        exec("pgvc_it_dev", """
                CREATE SCHEMA app;
                CREATE TABLE app.users(id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, email text NOT NULL UNIQUE,
                                       created_at timestamptz DEFAULT now());
                CREATE TABLE app.orders(id serial PRIMARY KEY, user_id bigint REFERENCES app.users(id) ON DELETE CASCADE,
                                        amount numeric(10,2) CHECK (amount > 0), status varchar(20) NOT NULL DEFAULT 'new');
                CREATE TABLE app.events(id bigint NOT NULL, ts date NOT NULL, payload jsonb, PRIMARY KEY (id, ts))
                    PARTITION BY RANGE (ts);
                CREATE TABLE app.events_2026_01 PARTITION OF app.events FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');
                CREATE TABLE app.events_default PARTITION OF app.events DEFAULT;
                CREATE INDEX events_payload_idx ON app.events (id) WHERE payload IS NOT NULL;
                CREATE INDEX orders_status_idx ON app.orders(status) WHERE status <> 'done';
                CREATE SEQUENCE app.invoice_no START 1000 INCREMENT 5;
                CREATE VIEW app.v_orders AS SELECT o.id, o.amount, u.email FROM app.orders o JOIN app.users u ON u.id = o.user_id;
                CREATE VIEW app.v_big_orders AS SELECT * FROM app.v_orders WHERE amount > 100;
                CREATE MATERIALIZED VIEW app.mv_totals AS SELECT user_id, sum(amount) total FROM app.orders GROUP BY user_id;
                CREATE UNIQUE INDEX mv_totals_user_idx ON app.mv_totals(user_id);
                CREATE TABLE "Mixed Case"("Select" int, "order" text);
                """);
        WorkingTree wt = svc.workingTree("dev");
        assertTrue(wt.hasChanges());
        assertFalse(wt.auditLog().isEmpty(), "event triggers must log DDL");
        CommitResult c1 = svc.commitWorkingTree("dev", new CommitRequest("Initial schema", "alice", null, null, null));
        wt = svc.workingTree("dev");
        assertFalse(wt.hasChanges(), wt.upSql());
        assertTrue(wt.auditLog().isEmpty());

        // ---- Mode A: renames, type change under views
        exec("pgvc_it_dev", """
                ALTER TABLE app.users RENAME TO customers;
                ALTER TABLE app.customers RENAME COLUMN email TO email_address;
                DROP VIEW app.v_big_orders; DROP VIEW app.v_orders; DROP MATERIALIZED VIEW app.mv_totals;
                ALTER TABLE app.orders ALTER COLUMN amount TYPE numeric(12,2);
                CREATE VIEW app.v_orders AS SELECT o.id, o.amount, u.email_address AS email
                    FROM app.orders o JOIN app.customers u ON u.id = o.user_id;
                CREATE VIEW app.v_big_orders AS SELECT * FROM app.v_orders WHERE amount > 100;
                CREATE MATERIALIZED VIEW app.mv_totals AS SELECT user_id, sum(amount) total FROM app.orders GROUP BY user_id;
                CREATE UNIQUE INDEX mv_totals_user_idx ON app.mv_totals(user_id);
                ALTER TABLE app.orders ADD COLUMN note text;
                ALTER TABLE app.orders DROP COLUMN status;
                CREATE TABLE app.events_2026_02 PARTITION OF app.events FOR VALUES FROM ('2026-02-01') TO ('2026-03-01');
                ALTER INDEX app.events_payload_idx RENAME TO events_payload_partial;
                ALTER TABLE app.customers ADD CONSTRAINT email_chk CHECK (email_address LIKE '%@%');
                ALTER SEQUENCE app.invoice_no INCREMENT 10;
                """);
        wt = svc.workingTree("dev");
        assertTrue(wt.upSql().contains("ALTER TABLE app.users RENAME TO customers;"), wt.upSql());
        assertTrue(wt.upSql().contains("RENAME COLUMN email TO email_address;"), wt.upSql());
        assertTrue(wt.upSql().contains("ALTER INDEX app.events_payload_idx RENAME TO events_payload_partial;"), wt.upSql());
        assertOrdered(wt.upSql(), "DROP VIEW app.v_big_orders;", "DROP VIEW app.v_orders;", "TYPE numeric(12,2)",
                "CREATE VIEW app.v_orders", "CREATE VIEW app.v_big_orders");
        assertThrows(PgvcException.class, () -> svc.commitWorkingTree("dev",
                new CommitRequest("stale", "alice", null, null, "0000")), "stale expectedHead must be rejected");
        CommitResult c2 = svc.commitWorkingTree("dev",
                new CommitRequest("Rename users, widen amount", "alice", null, null, c1.commit().hash()));

        // ---- Mode B: execute & commit
        CommitResult c3 = svc.executeAndCommit("dev", new ExecuteRequest("Add audit table", "bob", """
                CREATE TABLE app.audit(id bigserial PRIMARY KEY, at timestamptz NOT NULL DEFAULT now(), data jsonb);
                CREATE VIEW app.v_customers AS SELECT id, email_address FROM app.customers;
                """, null, false));
        assertFalse(svc.workingTree("dev").hasChanges());
        assertTrue(svc.commit("dev", c3.commit().hash()).downSql().contains("DROP TABLE app.audit;"));
        assertThrows(PgvcException.class, () -> svc.executeAndCommit("dev",
                new ExecuteRequest("broken", "bob", "CREATE TABLE app.x(id int); SELECT 1/0;", null, false)));
        assertThrows(PgvcException.class, () -> svc.executeAndCommit("dev",
                new ExecuteRequest("tx", "bob", "BEGIN;\nCREATE TABLE app.y(id int);\nCOMMIT;", null, false)));
        assertFalse(svc.workingTree("dev").hasChanges(), "failed migration must be rolled back completely");

        // ---- push dev -> prod
        PushResult pushed = svc.push("prod", new PushRequest("dev", c3.commit().hash(), false));
        assertEquals(3, pushed.appliedCommits().size());
        WorkingTree prodWt = svc.workingTree("prod");
        assertFalse(prodWt.hasChanges(), "UP SQL must reproduce the snapshot exactly:\n" + prodWt.upSql());

        // ---- DOWN SQL of every commit restores the parent schema
        List<CommitResult> newestFirst = List.of(c3, c2, c1);
        for (int i = 0; i < newestFirst.size(); i++) {
            CommitDetails det = svc.commit("prod", newestFirst.get(i).commit().hash());
            try (Connection c = DriverManager.getConnection(url("pgvc_it_prod"), USER, PASSWORD)) {
                c.setAutoCommit(false);
                for (int j = 0; j <= i; j++) {
                    try (Statement st = c.createStatement()) {
                        st.execute(svc.commit("prod", newestFirst.get(j).commit().hash()).downSql());
                    }
                }
                DatabaseSnapshot after = new Introspector().snapshot(c);
                DatabaseSnapshot parent = det.parentHash() == null ? DatabaseSnapshot.empty()
                        : svc.commit("prod", det.parentHash()).snapshot();
                DiffResult r = diff.diff(parent, after);
                assertFalse(r.hasChanges(), "DOWN of '" + det.message() + "' left differences:\n" + r.sql());
                c.rollback();
            }
        }

        // ---- drift on prod blocks push
        exec("pgvc_it_prod", "ALTER TABLE app.audit ADD COLUMN hotfix int");
        exec("pgvc_it_dev", "CREATE INDEX audit_at_idx ON app.audit(at)");
        CommitResult c4 = svc.commitWorkingTree("dev", new CommitRequest("Index on audit", "alice", null, null, c3.commit().hash()));
        PgvcException dirty = assertThrows(PgvcException.class,
                () -> svc.push("prod", new PushRequest("dev", c4.commit().hash(), false)));
        assertTrue(dirty.getMessage().contains("uncommitted"), dirty.getMessage());
        exec("pgvc_it_prod", "ALTER TABLE app.audit DROP COLUMN hotfix");
        assertEquals(1, svc.push("prod", new PushRequest("dev", c4.commit().hash(), false)).appliedCommits().size());

        // ---- offline bundle (executed like psql would: one script, BEGIN ... COMMIT)
        Bundle bundle = svc.exportBundle("dev", c1.commit().hash(), c4.commit().hash());
        exec("pgvc_it_offline", bundle.sql());
        assertEquals(c4.commit().hash(), query("pgvc_it_offline", "SELECT commit_hash FROM _pgvc.refs WHERE ref_name = 'HEAD'"));
        assertEquals("4", query("pgvc_it_offline", "SELECT count(*) FROM _pgvc.commits"));
        assertFalse(svc.workingTree("offline").hasChanges());
        SQLException again = assertThrows(SQLException.class, () -> exec("pgvc_it_offline", bundle.sql()));
        assertTrue(again.getMessage().contains("already applied"), again.getMessage());

        recreate("pgvc_it_offline");
        Bundle tail = svc.exportBundle("dev", null, c4.commit().hash());
        SQLException mismatch = assertThrows(SQLException.class, () -> exec("pgvc_it_offline", tail.sql()));
        assertTrue(mismatch.getMessage().contains("HEAD mismatch"), mismatch.getMessage());
        assertEquals(null, query("pgvc_it_offline", "SELECT to_regclass('_pgvc.commits')::text"),
                "a failed bundle must not leave anything behind");

        // ---- graph with environment badges
        Graph graph = svc.graph();
        assertEquals(4, graph.commits().size());
        assertTrue(graph.environments().stream()
                .anyMatch(e -> e.id().equals("prod") && c4.commit().hash().equals(e.head())));
        assertTrue(graph.commits().stream()
                .filter(c -> c.hash().equals(c4.commit().hash()))
                .allMatch(c -> c.environments().containsAll(List.of("dev", "prod"))));
    }
}
