package com.vodianoy.pgvc.execution;

import com.vodianoy.pgvc.common.ApiException;
import com.vodianoy.pgvc.model.schema.DatabaseSnapshot;
import com.vodianoy.pgvc.model.schema.TableModel;
import com.vodianoy.pgvc.safety.RiskLevel;
import com.vodianoy.pgvc.safety.SqlSafetyReport;
import com.vodianoy.pgvc.storage.CommitNode;
import com.vodianoy.pgvc.support.TestDatabases;
import com.vodianoy.pgvc.support.TestDatabases.Services;
import com.vodianoy.pgvc.vcs.CommitRequest;
import com.vodianoy.pgvc.vcs.CommitResult;
import com.vodianoy.pgvc.vcs.DryRunResult;
import com.vodianoy.pgvc.vcs.ExecuteMigrationRequest;
import com.vodianoy.pgvc.vcs.PushResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static com.vodianoy.pgvc.support.TestDatabases.envId;
import static com.vodianoy.pgvc.support.TestDatabases.query;
import static com.vodianoy.pgvc.support.TestDatabases.recreate;
import static com.vodianoy.pgvc.support.TestDatabases.sql;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Size statistics, dry-run, multi-step migrations, timeouts and step-wise push on real databases. */
@EnabledIfEnvironmentVariable(named = "PGVC_TEST_JDBC_URL", matches = ".+")
class SafeExecutionIntegrationTest {

    private static final String DEV_DB = "pgvc_step10_dev";
    private static final String PROD_DB = "pgvc_step10_prod";

    @TempDir
    Path dir;

    private Services s;
    private final String dev = envId(DEV_DB);
    private final String prod = envId(PROD_DB);

    @BeforeEach
    void setUp() throws Exception {
        recreate(DEV_DB);
        recreate(PROD_DB);
        String schema = """
                CREATE TABLE public.customers (id int PRIMARY KEY, email text);
                CREATE TABLE public.orders (id int PRIMARY KEY, customer_id int, amount numeric(10,2), note text);
                INSERT INTO public.customers SELECT g, 'c' || g || '@x' FROM generate_series(1, 1000) g;
                INSERT INTO public.orders SELECT g, 1 + g % 1000, g % 500, md5(g::text) FROM generate_series(1, 150000) g;
                CREATE TABLE public.events (id int, at date NOT NULL) PARTITION BY RANGE (at);
                CREATE TABLE public.events_2026 PARTITION OF public.events FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');
                INSERT INTO public.events SELECT g, DATE '2026-01-01' + g % 300 FROM generate_series(1, 20000) g;
                ANALYZE;
                """;
        sql(DEV_DB, schema);
        s = new Services(dir, DEV_DB, PROD_DB);
        s.storage.initializeEnvironment(dev);
        s.vc.commitWorkingTree(dev, new CommitRequest("Baseline", "dev", null, null, null));
    }

    @AfterEach
    void tearDown() {
        s.close();
    }

    private ExecuteMigrationRequest migration(String message, String up) {
        return new ExecuteMigrationRequest(message, "dev", up, null, false, null, null, null, null, false);
    }

    private ExecuteMigrationRequest dry(String up, String pre, String post) {
        return new ExecuteMigrationRequest("dry", "dev", up, null, false, "2s", "30s", pre, post, true);
    }

    @Test
    void tableSizeStatisticsAreIntrospected() {
        DatabaseSnapshot live = s.introspector.introspect(dev);
        TableModel orders = live.getTables().get("public.orders");
        assertThat(orders.getEstimatedRows()).isBetween(140_000L, 160_000L);
        assertThat(orders.getTotalSizeBytes()).isGreaterThan(1024L * 1024);
        assertThat(orders.getPrettySize()).endsWith("MB");
        TableModel parent = live.getTables().get("public.events");
        TableModel partition = live.getTables().get("public.events_2026");
        assertThat(partition.getEstimatedRows()).isEqualTo(20_000);
        // a partitioned table has no storage of its own: its statistics are the sums of its partitions
        assertThat(parent.getEstimatedRows()).isEqualTo(partition.getEstimatedRows());
        assertThat(parent.getTotalSizeBytes()).isGreaterThanOrEqualTo(partition.getTotalSizeBytes());

        // the analyzer sees the real size: an index build on 150k rows blocks writes for a while
        SqlSafetyReport report = s.vc.analyzeSql(dev, "CREATE INDEX orders_note_idx ON public.orders (note);");
        assertThat(report.overall().getRiskLevel()).isEqualTo(RiskLevel.DANGEROUS);
        assertThat(report.overall().getZeroDowntimeAlternativeSql()).contains("CREATE INDEX CONCURRENTLY orders_note_idx");
        // working tree changes carry the analysis too
        sql(DEV_DB, "ALTER TABLE public.orders ADD COLUMN status text");
        assertThat(s.vc.getWorkingTreeStatus(dev).diff().getChanges()).singleElement()
                .satisfies(c -> assertThat(c.getSafety()).isNotNull());
    }

    @Test
    void dryRunExecutesOnTheRealDatabaseAndRollsBack() throws Exception {
        int commitsBefore = s.storage.getCommitGraph(dev).commits().size();
        DryRunResult ok = s.vc.dryRun(dev, dry(
                "ALTER TABLE public.orders ADD COLUMN status text;\n-- pgvc:step\nCREATE INDEX CONCURRENTLY orders_status_idx ON public.orders (status);",
                "CREATE TABLE public.audit_tmp (id int);",
                "UPDATE public.orders SET status = 'new' WHERE id < 10;"));
        assertThat(ok.success()).isTrue();
        assertThat(ok.error()).isNull();
        assertThat(ok.totalSteps()).isEqualTo(2);
        assertThat(ok.steps()).hasSize(2);
        assertThat(ok.steps().get(1).transactional()).isFalse(); // runs outside of a transaction for real
        assertThat(ok.rewrittenStatements()).singleElement().satisfies(st -> assertThat(st).contains("CONCURRENTLY"));
        assertThat(ok.changes()).extracting(c -> c.getObjectName())
                .contains("public.orders.status", "public.audit_tmp", "public.orders_status_idx");
        assertThat(ok.generatedDownSql()).contains("DROP TABLE public.audit_tmp");
        assertThat(ok.timeouts()).isEqualTo(new Timeouts("2s", "30s"));
        assertThat(ok.safety().statements()).hasSizeGreaterThanOrEqualTo(4);
        // nothing stayed
        assertThat(query(DEV_DB, "SELECT count(*) FROM pg_attribute WHERE attrelid = 'public.orders'::regclass AND attname = 'status'"))
                .isEqualTo("0");
        assertThat(query(DEV_DB, "SELECT to_regclass('public.audit_tmp') IS NULL")).isEqualTo("t");
        assertThat(s.storage.getCommitGraph(dev).commits()).hasSize(commitsBefore);
        assertThat(s.storage.readAuditLog(dev)).isEmpty();

        DryRunResult failed = s.vc.dryRun(dev, dry("ALTER TABLE public.orders ADD COLUMN note text;", null, null));
        assertThat(failed.success()).isFalse();
        assertThat(failed.failedStep()).isEqualTo(1);
        assertThat(failed.error()).contains("note");
    }

    @Test
    void multiStepMigrationUsesSeparateTransactionsAndConcurrently() throws Exception {
        String up = """
                ALTER TABLE public.orders ADD CONSTRAINT orders_customer_fk FOREIGN KEY (customer_id) REFERENCES public.customers (id) NOT VALID;
                -- pgvc:step
                ALTER TABLE public.orders VALIDATE CONSTRAINT orders_customer_fk;
                -- pgvc:step
                CREATE INDEX CONCURRENTLY orders_customer_idx ON public.orders (customer_id);
                """;
        CommitResult result = s.vc.executeAndCommitSql(dev, migration("Zero-downtime FK", up));
        assertThat(result.steps()).hasSize(3);
        assertThat(result.steps()).extracting(StepReport::transactional).containsExactly(true, true, false);
        assertThat(query(DEV_DB, "SELECT convalidated FROM pg_constraint WHERE conname = 'orders_customer_fk'")).isEqualTo("t");
        assertThat(query(DEV_DB, "SELECT indisvalid FROM pg_index WHERE indexrelid = 'public.orders_customer_idx'::regclass"))
                .isEqualTo("t");
        assertThat(s.storage.getHead(dev)).contains(result.commit().hash());
        assertThat(s.vc.getCommit(dev, result.commit().hash()).upSql()).contains("-- pgvc:step").contains("CONCURRENTLY");
        assertThat(s.vc.getWorkingTreeStatus(dev).diff().isHasChanges()).isFalse();

        // ---- the commit is pushed step by step: the index is built CONCURRENTLY on prod too
        PushResult push = s.vc.pushCommitsToEnvironment(dev, prod, null);
        assertThat(push.appliedCommits()).hasSize(2);
        assertThat(query(PROD_DB, "SELECT indisvalid FROM pg_index WHERE indexrelid = 'public.orders_customer_idx'::regclass"))
                .isEqualTo("t");
        assertThat(s.storage.getHead(prod)).contains(result.commit().hash());

        // ---- the offline bundle is one transaction: CONCURRENTLY is removed there (and marked)
        assertThat(s.offline.generateOfflineSqlScript(dev, null, result.commit().hash()))
                .contains("-- pgvc: CONCURRENTLY removed").contains("CREATE INDEX orders_customer_idx");
    }

    @Test
    void failedStepLeavesEarlierStepsAsUncommittedChanges() {
        String head = s.storage.getHead(dev).orElseThrow();
        String up = "ALTER TABLE public.orders ADD COLUMN status text;\n-- pgvc:step\nALTER TABLE public.orders ADD COLUMN status text;";
        assertThatThrownBy(() -> s.vc.executeAndCommitSql(dev, migration("Broken", up)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage())
                        .contains("Step 2 of 2 failed").contains("Steps 1-1 are already applied"));
        assertThat(s.storage.getHead(dev)).contains(head);
        assertThat(s.vc.getWorkingTreeStatus(dev).diff().getChanges()).extracting(c -> c.getObjectName())
                .containsExactly("public.orders.status");

        // first step fails: nothing applied
        assertThatThrownBy(() -> s.vc.executeAndCommitSql(dev, migration("Broken", "ALTER TABLE nope ADD COLUMN x int;\n-- pgvc:step\nSELECT 1;")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).contains("Nothing was applied"));
    }

    @Test
    void lockAndStatementTimeoutsStopTheMigration() throws Exception {
        try (Connection blocker = DriverManager.getConnection(TestDatabases.url(DEV_DB), TestDatabases.USER, TestDatabases.PASSWORD)) {
            blocker.setAutoCommit(false);
            try (Statement st = blocker.createStatement()) {
                st.execute("LOCK TABLE public.orders IN ACCESS SHARE MODE");
                ExecuteMigrationRequest r = new ExecuteMigrationRequest("Blocked", "dev",
                        "ALTER TABLE public.orders ADD COLUMN blocked int;", null, false, "300ms", "30s", null, null, false);
                long t0 = System.currentTimeMillis();
                assertThatThrownBy(() -> s.vc.executeAndCommitSql(dev, r))
                        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage())
                                .contains("lock_timeout = 300ms"));
                assertThat(System.currentTimeMillis() - t0).isLessThan(10_000);
                // a dry-run reports the same instead of throwing
                DryRunResult dry = s.vc.dryRun(dev, new ExecuteMigrationRequest("d", "dev",
                        "ALTER TABLE public.orders ADD COLUMN blocked int;", null, false, "300ms", "30s", null, null, true));
                assertThat(dry.success()).isFalse();
                assertThat(dry.error()).contains("lock_timeout");
            }
            blocker.rollback();
        }
        assertThatThrownBy(() -> s.vc.executeAndCommitSql(dev, new ExecuteMigrationRequest("Slow", "dev",
                "CREATE TABLE public.slow (id int); SELECT pg_sleep(3);", null, false, "3s", "300ms", null, null, false)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).contains("statement_timeout = 300ms"));
        assertThat(query(DEV_DB, "SELECT to_regclass('public.slow') IS NULL")).isEqualTo("t");
        // the pooled connections were left without the session-level settings
        assertThat(s.environments.getJdbcTemplate(dev).queryForObject("SHOW lock_timeout", String.class)).isEqualTo("0");
    }

    @Test
    void preAndPostScriptsAreStoredWithTheCommit() {
        CommitResult r = s.vc.executeAndCommitSql(dev, new ExecuteMigrationRequest("With scripts", "dev",
                "ALTER TABLE public.customers ADD COLUMN vip boolean;", null, false, null, null,
                "CREATE TABLE public.migration_log (msg text);", "INSERT INTO public.migration_log VALUES ('done');", false));
        CommitNode commit = r.commit();
        assertThat(s.vc.getCommit(dev, commit.hash()).upSql()).contains("-- pgvc:pre-script")
                .contains("-- pgvc:post-script").contains("ADD COLUMN vip");
        assertThat(r.changes()).extracting(c -> c.getObjectName()).contains("public.customers.vip", "public.migration_log");
    }
}
