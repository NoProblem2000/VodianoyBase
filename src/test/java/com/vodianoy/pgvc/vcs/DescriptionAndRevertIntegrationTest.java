package com.vodianoy.pgvc.vcs;

import com.vodianoy.pgvc.common.ApiException;
import com.vodianoy.pgvc.diff.ChangeItem;
import com.vodianoy.pgvc.storage.CommitNode;
import com.vodianoy.pgvc.support.TestDatabases.Services;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static com.vodianoy.pgvc.support.TestDatabases.envId;
import static com.vodianoy.pgvc.support.TestDatabases.query;
import static com.vodianoy.pgvc.support.TestDatabases.recreate;
import static com.vodianoy.pgvc.support.TestDatabases.sql;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** v1.9.0 on a real database: commit descriptions (storage, graph, push, offline bundle, old installations) and Revert. */
@EnabledIfEnvironmentVariable(named = "PGVC_TEST_JDBC_URL", matches = ".+")
class DescriptionAndRevertIntegrationTest {

    private static final String DEV_DB = "pgvc_v190_dev";
    private static final String STAGE_DB = "pgvc_v190_stage";
    private static final String OFFLINE_DB = "pgvc_v190_offline";

    private static final String DESCRIPTION = "Customer contact data for the CRM sync.\nTicket: CRM-142\n\n- email is optional";

    @TempDir
    Path dir;

    private Services s;
    private final String dev = envId(DEV_DB);
    private final String stage = envId(STAGE_DB);
    private CommitNode baseline;

    @BeforeEach
    void setUp() throws Exception {
        recreate(DEV_DB);
        recreate(STAGE_DB);
        recreate(OFFLINE_DB);
        sql(DEV_DB, """
                CREATE SCHEMA app;
                CREATE TABLE app.customers (id bigint PRIMARY KEY, name text NOT NULL, logo bytea);
                INSERT INTO app.customers (id, name) VALUES (1, 'Acme'), (2, 'Globex');
                """);
        s = new Services(dir, DEV_DB, STAGE_DB, OFFLINE_DB);
        s.storage.initializeEnvironment(dev);
        baseline = s.vc.commitWorkingTree(dev, new CommitRequest("Baseline", "dev", null, null, null)).commit();
    }

    @AfterEach
    void tearDown() {
        s.close();
    }

    private CommitNode execute(String message, String upSql, String description) {
        return s.vc.executeAndCommitSql(dev, new ExecuteMigrationRequest(message, "alice", upSql, null, false, null, null,
                null, null, false, description)).commit();
    }

    // ------------------------------------------------------------------ description

    @Test
    void theDescriptionIsStoredNextToTheMessage() throws Exception {
        sql(DEV_DB, "ALTER TABLE app.customers ADD COLUMN email text");
        CommitNode c = s.vc.commitWorkingTree(dev, new CommitRequest("Customer email", "alice", null, null, null,
                "  " + DESCRIPTION + "  ")).commit();

        assertThat(c.message()).isEqualTo("Customer email");
        assertThat(c.description()).isEqualTo(DESCRIPTION);
        assertThat(query(DEV_DB, "SELECT description FROM _pgvc.commits WHERE hash = '" + c.hash() + "'")).isEqualTo(DESCRIPTION);
        assertThat(s.vc.getCommit(dev, c.hash()).description()).isEqualTo(DESCRIPTION);
        assertThat(s.vc.getGlobalGraph().commits()).filteredOn(g -> g.hash().equals(c.hash()))
                .singleElement().extracting(GlobalGraph.Commit::description).isEqualTo(DESCRIPTION);
        // commits without a description keep the hash format of older versions
        assertThat(s.vc.getCommit(dev, baseline.hash()).description()).isNull();

        // Mode B and blank descriptions
        CommitNode b = execute("Index on name", "CREATE INDEX customers_name_idx ON app.customers (name)", "   ");
        assertThat(b.description()).isNull();
    }

    @Test
    void theDescriptionTravelsWithPushAndTheOfflineBundle() throws Exception {
        CommitNode c = execute("Customer email", "ALTER TABLE app.customers ADD COLUMN email text", DESCRIPTION);

        s.vc.pushCommitsToEnvironment(dev, stage, null);
        assertThat(query(STAGE_DB, "SELECT description FROM _pgvc.commits WHERE hash = '" + c.hash() + "'")).isEqualTo(DESCRIPTION);

        String bundle = s.offline.generateOfflineSqlScript(dev, null, c.hash());
        assertThat(bundle).contains("INSERT INTO _pgvc.commits (hash, parent_hash, author, message, description, created_at")
                .contains("ADD COLUMN IF NOT EXISTS description");
        sql(OFFLINE_DB, bundle);
        assertThat(query(OFFLINE_DB, "SELECT description FROM _pgvc.commits WHERE hash = '" + c.hash() + "'")).isEqualTo(DESCRIPTION);
        assertThat(query(OFFLINE_DB, "SELECT count(*) FROM _pgvc.commits WHERE description IS NULL")).isEqualTo("1");
    }

    @Test
    void anInstallationOfAnOlderVersionGetsTheColumnWhenNeeded() throws Exception {
        String old = envId(OFFLINE_DB);
        sql(OFFLINE_DB, "CREATE TABLE public.items (id int PRIMARY KEY)");
        s.storage.initializeEnvironment(old);
        // _pgvc as installed by v1.8: no description column
        sql(OFFLINE_DB, "ALTER TABLE _pgvc.commits DROP COLUMN description");
        CommitNode first = s.vc.commitWorkingTree(old, new CommitRequest("Baseline", "ops", null, null, null)).commit();
        // reading works without the column
        assertThat(s.vc.getCommit(old, first.hash()).description()).isNull();
        assertThat(s.storage.getCommitGraph(old).commits()).extracting(CommitNode::hash).containsExactly(first.hash());
        assertThat(query(OFFLINE_DB, "SELECT count(*) FROM pg_attribute WHERE attrelid = '_pgvc.commits'::regclass"
                + " AND attname = 'description' AND NOT attisdropped")).isEqualTo("0");
        // the first description adds the column
        sql(OFFLINE_DB, "ALTER TABLE public.items ADD COLUMN name text");
        CommitNode described = s.vc.commitWorkingTree(old, new CommitRequest("Item name", "ops", null, null, null,
                DESCRIPTION)).commit();
        assertThat(query(OFFLINE_DB, "SELECT description FROM _pgvc.commits WHERE hash = '" + described.hash() + "'"))
                .isEqualTo(DESCRIPTION);
        assertThat(s.vc.getCommit(old, described.hash()).description()).isEqualTo(DESCRIPTION);
        assertThat(s.vc.getWorkingTreeStatus(old).diff().isHasChanges()).isFalse();
    }

    // ------------------------------------------------------------------ revert

    @Test
    void revertUndoesOneCommitFromTheMiddleOfTheHistory() throws Exception {
        CommitNode dropLogo = execute("Drop logo", "ALTER TABLE app.customers DROP COLUMN logo", null);
        CommitNode orders = execute("Orders", "CREATE TABLE app.orders (id bigint PRIMARY KEY, customer_id bigint)", null);

        RevertPlan plan = s.vc.getRevertPlan(dev, dropLogo.hash());
        assertThat(plan.targetIsHead()).isFalse();
        assertThat(plan.laterCommits()).isEqualTo(1);
        assertThat(plan.revertSql()).contains("ALTER TABLE app.customers ADD COLUMN logo bytea;");
        assertThat(plan.revertDownSql()).contains("DROP COLUMN logo");
        assertThat(plan.defaultMessage()).isEqualTo("Revert \"Drop logo\"");
        assertThat(plan.restoredObjects()).containsExactly("app.customers.logo");
        assertThat(plan.droppedObjects()).isEmpty();
        assertThat(plan.conflicts()).isEmpty();
        assertThat(plan.dirty()).isFalse();

        CommitResult r = s.vc.revertCommit(new RevertRequest(dev, dropLogo.hash(), "bob", null, null, null, false, null,
                null, orders.hash()));
        CommitNode revert = r.commit();
        assertThat(revert.parentHash()).isEqualTo(orders.hash());
        assertThat(revert.message()).isEqualTo("Revert \"Drop logo\"");
        assertThat(revert.description()).isEqualTo("Reverts commit " + dropLogo.hash().substring(0, 7) + ".");
        assertThat(s.storage.getHead(dev)).contains(revert.hash());
        // the column is back (empty), the later commit stays
        assertThat(query(DEV_DB, "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'app'"
                + " AND table_name = 'customers' AND column_name = 'logo'")).isEqualTo("1");
        assertThat(query(DEV_DB, "SELECT to_regclass('app.orders') IS NOT NULL")).isEqualTo("t");
        // DOWN SQL of the revert = UP SQL of the reverted commit
        assertThat(s.vc.getCommit(dev, revert.hash()).downSql()).isEqualTo(s.vc.getCommit(dev, dropLogo.hash()).upSql());
        assertThat(s.vc.getWorkingTreeStatus(dev).diff().isHasChanges()).isFalse();
        assertThat(query(DEV_DB, "SELECT count(*) FROM _pgvc.ddl_audit_log")).isEqualTo("0");
    }

    @Test
    void revertWarnsAboutDataAndLaterChangesAndTakesEditedSql() throws Exception {
        CommitNode notes = execute("Notes", "CREATE TABLE app.notes (id int PRIMARY KEY, body text)", null);
        execute("Notes author", "ALTER TABLE app.notes ADD COLUMN author text", null);

        RevertPlan plan = s.vc.getRevertPlan(dev, notes.hash());
        assertThat(plan.droppedObjects()).containsExactly("app.notes");
        assertThat(plan.conflicts()).containsExactly("app.notes");
        assertThat(plan.revertSql()).contains("DROP TABLE app.notes;");
        assertThat(plan.safety().overall().isCausesDataLoss()).isTrue();

        // the user reviews the SQL: keep the table, drop only the later column
        String edited = "ALTER TABLE app.notes DROP COLUMN author;";
        CommitNode revert = s.vc.revertCommit(new RevertRequest(dev, notes.hash(), "bob", "Revert notes partially",
                "Only the author column", edited, false, null, null, null)).commit();
        assertThat(revert.message()).isEqualTo("Revert notes partially");
        assertThat(revert.description()).isEqualTo("Only the author column");
        assertThat(query(DEV_DB, "SELECT to_regclass('app.notes') IS NOT NULL")).isEqualTo("t");
        // an edited script is not the inverse of the reverted commit: its DOWN SQL comes from the schema diff
        assertThat(s.vc.getCommit(dev, revert.hash()).downSql()).contains("ADD COLUMN author text");
    }

    @Test
    void revertRefusesWhatIsNotInTheHistoryAndAMovedHead() throws Exception {
        CommitNode email = execute("Email", "ALTER TABLE app.customers ADD COLUMN email text", null);
        s.vc.rollbackToCommit(dev, baseline.hash(), null);
        // email is no longer in the history of dev (an abandoned branch)
        assertThatThrownBy(() -> s.vc.getRevertPlan(dev, email.hash())).isInstanceOf(ApiException.class)
                .hasMessageContaining("is not in the history");
        assertThatThrownBy(() -> s.vc.revertCommit(new RevertRequest(dev, baseline.hash(), "bob", null, null, null, false,
                null, null, email.hash()))).isInstanceOf(ApiException.class).hasMessageContaining("HEAD");
        // uncommitted changes are reported (the revert refuses to run unless they are allowed)
        sql(DEV_DB, "ALTER TABLE app.customers ADD COLUMN phone text");
        assertThat(s.vc.getRevertPlan(dev, baseline.hash()).dirty()).isTrue();
    }

    @Test
    void theRevertPlanListsWhatTheCommitChanged() {
        CommitNode email = execute("Email", "ALTER TABLE app.customers ADD COLUMN email text", "why");
        RevertPlan plan = s.vc.getRevertPlan(dev, email.hash());
        assertThat(plan.targetIsHead()).isTrue();
        assertThat(plan.target().description()).isEqualTo("why");
        assertThat(plan.targetChanges()).extracting(ChangeItem::getObjectName).containsExactly("app.customers.email");
        assertThat(plan.droppedObjects()).containsExactly("app.customers.email");
    }
}
