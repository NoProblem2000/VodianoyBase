package com.vodianoy.pgvc.vcs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vodianoy.pgvc.common.ApiException;
import com.vodianoy.pgvc.config.PgVcProperties;
import com.vodianoy.pgvc.diff.ChangeItem;
import com.vodianoy.pgvc.diff.ChangeType;
import com.vodianoy.pgvc.diff.ObjectType;
import com.vodianoy.pgvc.diff.SchemaDiffEngine;
import com.vodianoy.pgvc.diff.SchemaDiffResult;
import com.vodianoy.pgvc.environment.Environment;
import com.vodianoy.pgvc.environment.EnvironmentManagerService;
import com.vodianoy.pgvc.introspection.PostgresSchemaIntrospector;
import com.vodianoy.pgvc.storage.CommitNode;
import com.vodianoy.pgvc.storage.PgVcStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end version control on a real PostgreSQL 11+ server (enabled when PGVC_TEST_JDBC_URL is set):
 * baseline commit, Mode A with renames and view dependencies, Mode B, UP scripts replayed on an empty database,
 * rollback with verification.
 */
@EnabledIfEnvironmentVariable(named = "PGVC_TEST_JDBC_URL", matches = ".+")
class VersionControlServiceIntegrationTest {

    private static final String BASE_URL = System.getenv("PGVC_TEST_JDBC_URL");
    private static final String USER = System.getenv().getOrDefault("PGVC_TEST_USER", "postgres");
    private static final String PASSWORD = System.getenv().getOrDefault("PGVC_TEST_PASSWORD", "");
    private static final String DEV = "pgvc_step3_dev";
    private static final String REPLICA = "pgvc_step3_replica";

    @TempDir
    Path dir;

    private EnvironmentManagerService environments;
    private PgVcStorageService storage;
    private PostgresSchemaIntrospector introspector;
    private VersionControlService vc;
    private final SchemaDiffEngine engine = new SchemaDiffEngine();

    private static String url(String db) {
        return BASE_URL.substring(0, BASE_URL.lastIndexOf('/') + 1) + db;
    }

    private static void sql(String db, String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(url(db), USER, PASSWORD); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        String adminDb = BASE_URL.substring(BASE_URL.lastIndexOf('/') + 1);
        for (String db : List.of(DEV, REPLICA)) {
            sql(adminDb, "DROP DATABASE IF EXISTS " + db);
            sql(adminDb, "CREATE DATABASE " + db);
        }
        sql(DEV, new ClassPathResource("fixtures/rich-schema.sql").getContentAsString(StandardCharsets.UTF_8));

        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        PgVcProperties props = new PgVcProperties();
        props.setEnvironmentsFile(dir.resolve("pgvc-environments.json").toString());
        environments = new EnvironmentManagerService(props, mapper);
        environments.addEnvironment(Environment.builder().name("Dev").jdbcUrl(url(DEV)).username(USER).password(PASSWORD).build());
        environments.addEnvironment(Environment.builder().name("Replica").jdbcUrl(url(REPLICA)).username(USER).password(PASSWORD).build());
        storage = new PgVcStorageService(environments);
        introspector = new PostgresSchemaIntrospector(environments);
        vc = new VersionControlService(environments, storage, introspector, engine, mapper, new CommitHistory(storage));
        storage.initializeEnvironment("dev");
    }

    @AfterEach
    void tearDown() {
        environments.destroy();
    }

    private CommitNode baseline() {
        return vc.commitWorkingTree("dev", new CommitRequest("Baseline", "alice", null, null, null)).commit();
    }

    private static final String MODE_A_CHANGES = """
            ALTER TABLE sales.orders RENAME TO purchases;
            ALTER TABLE sales.purchases RENAME COLUMN note TO remark;
            DROP VIEW sales.v_big_orders; DROP VIEW sales.v_orders; DROP MATERIALIZED VIEW billing.mv_totals;
            ALTER TABLE sales.purchases ALTER COLUMN amount TYPE numeric(14,2);
            CREATE VIEW sales.v_orders AS
                SELECT o.id, o.amount, c.email FROM sales.purchases o JOIN sales.customers c ON c.id = o.customer_id;
            CREATE VIEW sales.v_big_orders AS SELECT * FROM sales.v_orders WHERE amount > 100;
            CREATE MATERIALIZED VIEW billing.mv_totals AS
                SELECT customer_id, sum(amount) AS total FROM sales.purchases GROUP BY customer_id;
            CREATE UNIQUE INDEX mv_totals_customer_idx ON billing.mv_totals (customer_id);
            ALTER TABLE sales.purchases ADD COLUMN channel text DEFAULT 'web';
            CREATE TABLE billing.events_2026_02 PARTITION OF billing.events FOR VALUES FROM ('2026-02-01') TO ('2026-03-01');
            ALTER SEQUENCE sales.invoice_no INCREMENT 10;
            """;

    @Test
    void workingTreeOfAnEmptyHistoryContainsTheWholeSchema() {
        WorkingTreeStatus status = vc.getWorkingTreeStatus("dev");

        assertThat(status.head()).isNull();
        assertThat(status.diff().isHasChanges()).isTrue();
        assertThat(status.diff().getChanges()).allMatch(c -> c.getChangeType() == ChangeType.ADDED);
        assertThat(status.diff().getChanges()).extracting(ChangeItem::getObjectName)
                .contains("sales", "sales.orders", "billing.events_2026_01", "sales.v_orders", "sales.invoice_no");
    }

    @Test
    void fullWorkflow() throws Exception {
        // ---- baseline
        CommitNode c1 = baseline();
        assertThat(storage.getHead("dev")).contains(c1.hash());
        assertThat(vc.getWorkingTreeStatus("dev").diff().isHasChanges()).isFalse();

        // ---- Mode A: changes from an external IDE, captured by the event triggers
        sql(DEV, MODE_A_CHANGES);
        WorkingTreeStatus wt = vc.getWorkingTreeStatus("dev");
        SchemaDiffResult diff = wt.diff();
        assertThat(wt.head()).isEqualTo(c1.hash());
        assertThat(wt.auditLog()).isNotEmpty();
        ChangeItem rename = diff.getChanges().stream()
                .filter(c -> c.getChangeType() == ChangeType.RENAMED && c.getObjectType() == ObjectType.TABLE)
                .findFirst().orElseThrow();
        assertThat(rename.getUpSql()).isEqualTo("ALTER TABLE sales.orders RENAME TO purchases;");
        assertThat(rename.getDownSql()).isEqualTo("ALTER TABLE sales.purchases RENAME TO orders;");
        assertThat(diff.getUpSql()).contains("ALTER TABLE sales.purchases RENAME COLUMN note TO remark;");
        String up = diff.getUpSql();
        assertThat(up.indexOf("DROP VIEW IF EXISTS sales.v_big_orders"))
                .isLessThan(up.indexOf("DROP VIEW IF EXISTS sales.v_orders"));
        assertThat(up.indexOf("DROP VIEW IF EXISTS sales.v_orders")).isLessThan(up.indexOf("TYPE numeric(14,2)"));
        assertThat(up.indexOf("TYPE numeric(14,2)")).isLessThan(up.indexOf("CREATE OR REPLACE VIEW sales.v_orders"));

        assertThatThrownBy(() -> vc.commitWorkingTree("dev", new CommitRequest("stale", "bob", null, null, "0000")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        String editedUp = "-- reviewed by alice\n" + diff.getUpSql();
        CommitResult r2 = vc.commitWorkingTree("dev", new CommitRequest("Rename orders, widen amount", "alice",
                editedUp, diff.getDownSql(), c1.hash()));
        CommitNode c2 = r2.commit();
        assertThat(c2.parentHash()).isEqualTo(c1.hash());
        assertThat(vc.getCommit("dev", c2.hash()).upSql()).isEqualTo(editedUp);
        assertThat(vc.getCommit("dev", c2.hash()).ddlLogHistory()).isNotNull();
        assertThat(storage.readAuditLog("dev")).isEmpty();
        assertThat(vc.getWorkingTreeStatus("dev").diff().isHasChanges()).isFalse();

        // ---- Mode B: execute & commit in one transaction
        CommitResult r3 = vc.executeAndCommitSql("dev", new ExecuteMigrationRequest("Add audit table", "bob", """
                CREATE TABLE sales.audit (id bigserial PRIMARY KEY, at timestamptz NOT NULL DEFAULT now(), data jsonb);
                CREATE VIEW sales.v_audit AS SELECT id, at FROM sales.audit;
                """, null, false));
        CommitNode c3 = r3.commit();
        assertThat(r3.changes()).extracting(ChangeItem::getObjectName).contains("sales.audit", "sales.v_audit");
        assertThat(vc.getCommit("dev", c3.hash()).downSql()).contains("DROP TABLE sales.audit;")
                .contains("DROP VIEW IF EXISTS sales.v_audit;");
        assertThat(vc.getWorkingTreeStatus("dev").diff().isHasChanges()).isFalse();

        assertThatThrownBy(() -> vc.executeAndCommitSql("dev", new ExecuteMigrationRequest("broken", "bob",
                "CREATE TABLE sales.half_done (id int); SELECT 1/0;", null, false)))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> vc.executeAndCommitSql("dev", new ExecuteMigrationRequest("tx", "bob",
                "BEGIN;\nCREATE TABLE sales.x (id int);\nCOMMIT;", null, false)))
                .isInstanceOf(ApiException.class);
        assertThat(storage.getHead("dev")).contains(c3.hash());
        assertThat(vc.getWorkingTreeStatus("dev").diff().isHasChanges()).as("failed migration left nothing").isFalse();

        sql(DEV, "CREATE TABLE sales.uncommitted (id int)");
        assertThatThrownBy(() -> vc.executeAndCommitSql("dev", new ExecuteMigrationRequest("dirty", "bob",
                "CREATE TABLE sales.y (id int);", null, false)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).contains("uncommitted"));
        sql(DEV, "DROP TABLE sales.uncommitted; DELETE FROM _pgvc.ddl_audit_log;");

        // ---- the stored UP scripts rebuild the schema on an empty database
        for (CommitNode c : List.of(c1, c2, c3)) {
            sql(REPLICA, vc.getCommit("dev", c.hash()).upSql());
        }
        SchemaDiffResult replicaDiff = engine.diff(vc.getCommit("dev", c3.hash()).schemaSnapshot(),
                introspector.introspect("replica"));
        assertThat(replicaDiff.isHasChanges()).as("replayed UP scripts:\n" + replicaDiff.getUpSql()).isFalse();

        // ---- rollback
        RollbackPlan plan = vc.getRollbackPlan("dev", c1.hash());
        assertThat(plan.commitsToRevert()).extracting(CommitNode::hash).containsExactly(c3.hash(), c2.hash());
        assertThat(plan.rollbackSql()).contains("-- >>> revert").contains("DROP TABLE sales.audit;");

        assertThatThrownBy(() -> vc.rollbackToCommit("dev", c1.hash(), "SELECT 1;"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).contains("still differs"));
        assertThat(storage.getHead("dev")).contains(c3.hash());

        sql(DEV, "CREATE TABLE sales.uncommitted (id int)");
        assertThatThrownBy(() -> vc.rollbackToCommit("dev", c1.hash(), null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).contains("uncommitted"));
        sql(DEV, "DROP TABLE sales.uncommitted");

        RollbackResult rolledBack = vc.rollbackToCommit("dev", c1.hash(), null);
        assertThat(rolledBack.verified()).isTrue();
        assertThat(rolledBack.revertedCommits()).hasSize(2);
        assertThat(storage.getHead("dev")).contains(c1.hash());
        assertThat(storage.readAuditLog("dev")).isEmpty();
        assertThat(vc.getWorkingTreeStatus("dev").diff().isHasChanges()).isFalse();
        assertThat(introspector.introspect("dev").getTables()).containsKey("sales.orders").doesNotContainKey("sales.purchases");

        // ---- history is kept; a new commit starts a branch from the restored commit
        assertThat(storage.getCommitGraph("dev").commits()).hasSize(3);
        CommitNode branch = vc.executeAndCommitSql("dev", new ExecuteMigrationRequest("Branch", "carol",
                "ALTER TABLE sales.orders ADD COLUMN coupon text;", null, false)).commit();
        assertThat(branch.parentHash()).isEqualTo(c1.hash());
        assertThatThrownBy(() -> vc.getRollbackPlan("dev", c3.hash()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).contains("not an ancestor"));
    }

    @Test
    void postgresFeatureNotSupportedErrorIsReportedAndTheConnectionSurvives() {
        baseline();
        // SQLSTATE 0A000: the column is read by sales.v_orders
        assertThatThrownBy(() -> vc.executeAndCommitSql("dev", new ExecuteMigrationRequest("alter under view", "bob",
                "ALTER TABLE sales.orders ALTER COLUMN amount TYPE numeric(20,2);", null, false)))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("used by a view");

        CommitResult next = vc.executeAndCommitSql("dev", new ExecuteMigrationRequest("next", "bob",
                "ALTER TABLE sales.orders ADD COLUMN coupon text;", null, false));
        assertThat(next.changes()).extracting(ChangeItem::getObjectName).containsExactly("sales.orders.coupon");
    }
}
