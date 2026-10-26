package com.vodianoy.pgvc.vcs;

import com.vodianoy.pgvc.common.ApiException;
import com.vodianoy.pgvc.storage.CommitNode;
import com.vodianoy.pgvc.support.TestDatabases;
import com.vodianoy.pgvc.support.TestDatabases.Services;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static com.vodianoy.pgvc.support.TestDatabases.envId;
import static com.vodianoy.pgvc.support.TestDatabases.recreate;
import static com.vodianoy.pgvc.support.TestDatabases.sql;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** compareEnvironments / pushCommitsToEnvironment / global graph between two real databases. */
@EnabledIfEnvironmentVariable(named = "PGVC_TEST_JDBC_URL", matches = ".+")
class EnvironmentSyncIntegrationTest {

    private static final String DEV_DB = "pgvc_step4_sync_dev";
    private static final String PROD_DB = "pgvc_step4_sync_prod";

    @TempDir
    Path dir;

    private Services s;
    private final String dev = envId(DEV_DB);
    private final String prod = envId(PROD_DB);

    @BeforeEach
    void setUp() throws Exception {
        recreate(DEV_DB);
        recreate(PROD_DB);
        sql(DEV_DB, TestDatabases.fixture("rich-schema.sql"));
        s = new Services(dir, DEV_DB, PROD_DB);
        s.storage.initializeEnvironment(dev);
    }

    @AfterEach
    void tearDown() {
        s.close();
    }

    private CommitNode execute(String env, String message, String sql) {
        return s.vc.executeAndCommitSql(env, new ExecuteMigrationRequest(message, "tester", sql, null, false)).commit();
    }

    @Test
    void compareAndPushThroughAllHistoryRelations() throws Exception {
        CommitNode c1 = s.vc.commitWorkingTree(dev, new CommitRequest("Baseline", "alice", null, null, null)).commit();
        CommitNode c2 = execute(dev, "Notes", "CREATE TABLE sales.notes (id int PRIMARY KEY, body text);");

        // ---- prod has never been initialized
        EnvironmentComparison fresh = s.vc.compareEnvironments(dev, prod);
        assertThat(fresh.relation()).isEqualTo(HistoryRelation.TARGET_NOT_INITIALIZED);
        assertThat(fresh.commitsToPush()).extracting(CommitNode::hash).containsExactly(c1.hash(), c2.hash());
        assertThat(fresh.targetHasUncommittedChanges()).isFalse();
        assertThat(fresh.schemaDiff().isHasChanges()).isTrue();

        // ---- push the first commit only: _pgvc is installed automatically, the schema is verified
        PushResult first = s.vc.pushCommitsToEnvironment(dev, prod, c1.hash());
        assertThat(first.previousHead()).isNull();
        assertThat(first.newHead()).isEqualTo(c1.hash());
        assertThat(first.verified()).isTrue();
        assertThat(s.storage.getStatus(prod).ddlTriggerEnabled()).isTrue();
        assertThat(s.vc.getWorkingTreeStatus(prod).diff().isHasChanges()).isFalse();

        EnvironmentComparison behind = s.vc.compareEnvironments(dev, prod);
        assertThat(behind.relation()).isEqualTo(HistoryRelation.TARGET_BEHIND);
        assertThat(behind.commitsToPush()).extracting(CommitNode::hash).containsExactly(c2.hash());
        assertThat(behind.schemaDiff().getChanges()).extracting(c -> c.getObjectName()).contains("sales.notes");

        // ---- push the rest (target commit omitted = source HEAD)
        PushResult rest = s.vc.pushCommitsToEnvironment(dev, prod, null);
        assertThat(rest.appliedCommits()).extracting(CommitNode::hash).containsExactly(c2.hash());
        EnvironmentComparison same = s.vc.compareEnvironments(dev, prod);
        assertThat(same.relation()).isEqualTo(HistoryRelation.SAME);
        assertThat(same.schemaDiff().isHasChanges()).isFalse();
        assertThatThrownBy(() -> s.vc.pushCommitsToEnvironment(dev, prod, null)).isInstanceOf(ApiException.class);

        // ---- drift on prod blocks a push
        CommitNode c3 = execute(dev, "Notes author", "ALTER TABLE sales.notes ADD COLUMN author text;");
        sql(PROD_DB, "CREATE TABLE public.hotfix (id int)");
        assertThat(s.vc.compareEnvironments(dev, prod).targetHasUncommittedChanges()).isTrue();
        assertThatThrownBy(() -> s.vc.pushCommitsToEnvironment(dev, prod, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).contains("uncommitted"));
        sql(PROD_DB, "DROP TABLE public.hotfix; DELETE FROM _pgvc.ddl_audit_log;");

        // ---- source behind target
        EnvironmentComparison ahead = s.vc.compareEnvironments(prod, dev);
        assertThat(ahead.relation()).isEqualTo(HistoryRelation.TARGET_AHEAD);
        assertThat(ahead.commitsToPush()).isEmpty();

        // ---- diverged histories
        CommitNode c4 = execute(prod, "Prod hotfix", "CREATE INDEX notes_body_idx ON sales.notes (body);");
        EnvironmentComparison diverged = s.vc.compareEnvironments(dev, prod);
        assertThat(diverged.relation()).isEqualTo(HistoryRelation.DIVERGED);
        assertThat(diverged.commonAncestor()).isEqualTo(c2.hash());
        assertThatThrownBy(() -> s.vc.pushCommitsToEnvironment(dev, prod, c3.hash()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).contains("not an ancestor"));
        assertThat(s.storage.getHead(prod)).contains(c4.hash());

        // ---- one graph for all environments with HEAD badges
        GlobalGraph graph = s.vc.getGlobalGraph();
        assertThat(graph.commits()).extracting(GlobalGraph.Commit::hash)
                .containsExactlyInAnyOrder(c1.hash(), c2.hash(), c3.hash(), c4.hash());
        assertThat(graph.commits()).filteredOn(c -> c.hash().equals(c2.hash()))
                .singleElement().satisfies(c -> assertThat(c.environments()).containsExactlyInAnyOrder(dev, prod));
        assertThat(graph.commits()).filteredOn(c -> c.hash().equals(c4.hash()))
                .singleElement().satisfies(c -> assertThat(c.environments()).containsExactly(prod));
        assertThat(graph.environments()).extracting(GlobalGraph.EnvironmentHead::id, GlobalGraph.EnvironmentHead::head)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple(dev, c3.hash()),
                        org.assertj.core.groups.Tuple.tuple(prod, c4.hash()));
    }

    @Test
    void pushRefusesAnUntrackedNonEmptyTarget() throws Exception {
        s.vc.commitWorkingTree(dev, new CommitRequest("Baseline", "alice", null, null, null));
        sql(PROD_DB, "CREATE TABLE public.legacy (id int)");

        assertThat(s.vc.compareEnvironments(dev, prod).targetHasUncommittedChanges()).isTrue();
        assertThatThrownBy(() -> s.vc.pushCommitsToEnvironment(dev, prod, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).contains("no version history"));
        assertThat(s.storage.getHead(prod)).isEmpty();
    }

    @Test
    void failingCommitRollsBackThePush() throws Exception {
        CommitNode c1 = s.vc.commitWorkingTree(dev, new CommitRequest("Baseline", "alice", null, null, null)).commit();
        s.vc.pushCommitsToEnvironment(dev, prod, null);
        execute(dev, "Seed table", "CREATE TABLE public.seed (id int PRIMARY KEY);");
        // someone created the same table on prod by hand: the commit's up_sql fails there
        sql(PROD_DB, "CREATE TABLE public.seed (id int PRIMARY KEY)");

        assertThatThrownBy(() -> s.vc.pushCommitsToEnvironment(new PushRequest(dev, prod, null, true, false)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage())
                        .contains("failed on").contains("Nothing was applied"));
        assertThat(s.storage.getHead(prod)).contains(c1.hash());
        assertThat(s.storage.getCommitGraph(prod).commits()).hasSize(1);
    }
}
