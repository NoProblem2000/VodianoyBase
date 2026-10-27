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

/** Deleting commits left behind by rollbacks, across two real databases. */
@EnabledIfEnvironmentVariable(named = "PGVC_TEST_JDBC_URL", matches = ".+")
class CommitDeletionIntegrationTest {

    private static final String DEV_DB = "pgvc_step8_delete_dev";
    private static final String PROD_DB = "pgvc_step8_delete_prod";

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

    private void rollback(String env, String target) {
        s.vc.rollbackToCommit(env, new RollbackRequest(target, null, false, false));
    }

    @Test
    void abandonedCommitsAreDeletedEverywhereOnlyWhenNoEnvironmentUsesThem() throws Exception {
        CommitNode c1 = s.vc.commitWorkingTree(dev, new CommitRequest("Baseline", "alice", null, null, null)).commit();
        CommitNode c2 = execute(dev, "Notes", "CREATE TABLE sales.notes (id int PRIMARY KEY, body text);");
        CommitNode c3 = execute(dev, "Notes index", "CREATE INDEX notes_body_idx ON sales.notes (body);");
        s.vc.pushCommitsToEnvironment(dev, prod, null);

        // HEADs of both environments are on c3: nothing can be deleted
        DeletionPlan used = s.deletion.plan(c3.hash(), DeletionMode.COMMIT);
        assertThat(used.deletable()).isFalse();
        assertThat(used.blockedBy()).containsExactlyInAnyOrder(DEV_DB, PROD_DB);
        assertThatThrownBy(() -> s.deletion.delete(c3.hash(), DeletionMode.COMMIT))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).contains("Roll the environment"));

        // dev rolls back: c2, c3 are still used by prod
        rollback(dev, c1.hash());
        assertThat(s.deletion.plan(c2.hash(), DeletionMode.BRANCH).blockedBy()).containsExactly(PROD_DB);

        // prod rolls back too: the branch c2-c3 is abandoned everywhere
        rollback(prod, c1.hash());
        DeletionPlan branch = s.deletion.plan(c3.hash(), DeletionMode.BRANCH);
        assertThat(branch.deletable()).isTrue();
        assertThat(branch.commits()).extracting(GlobalGraph.Commit::hash).containsExactly(c3.hash(), c2.hash());
        assertThat(branch.environments()).containsExactlyInAnyOrder(dev, prod);
        // COMMIT mode on c3 alone: only c3
        assertThat(s.deletion.plan(c3.hash(), DeletionMode.COMMIT).commits()).hasSize(1);

        DeletionResult result = s.deletion.delete(c3.hash(), DeletionMode.BRANCH);
        assertThat(result.deletedCommits()).containsExactly(c3.hash(), c2.hash());
        assertThat(result.perEnvironment()).containsEntry(dev, 2).containsEntry(prod, 2);
        assertThat(s.storage.getCommitGraph(dev).commits()).extracting(CommitNode::hash).containsExactly(c1.hash());
        assertThat(s.storage.getCommitGraph(prod).commits()).extracting(CommitNode::hash).containsExactly(c1.hash());
        assertThat(s.vc.getGlobalGraph().commits()).extracting(GlobalGraph.Commit::hash).containsExactly(c1.hash());
        // the schema itself is untouched; the database is still at c1
        assertThat(s.vc.getWorkingTreeStatus(dev).diff().isHasChanges()).isFalse();
    }

    @Test
    void branchDeletionStopsWhereANewBranchContinues() throws Exception {
        CommitNode c1 = s.vc.commitWorkingTree(dev, new CommitRequest("Baseline", "alice", null, null, null)).commit();
        CommitNode c2 = execute(dev, "Experiment", "CREATE TABLE public.experiment (id int);");
        CommitNode c3 = execute(dev, "Experiment 2", "ALTER TABLE public.experiment ADD COLUMN note text;");
        rollback(dev, c2.hash());
        // a new commit on top of c2: c2 is live again, only c3 is abandoned
        CommitNode c4 = execute(dev, "Other way", "ALTER TABLE public.experiment ADD COLUMN remark text;");

        DeletionPlan plan = s.deletion.plan(c3.hash(), DeletionMode.BRANCH);
        assertThat(plan.commits()).extracting(GlobalGraph.Commit::hash).containsExactly(c3.hash());
        s.deletion.delete(c3.hash(), DeletionMode.BRANCH);
        assertThat(s.storage.getCommitGraph(dev).commits()).extracting(CommitNode::hash)
                .containsExactlyInAnyOrder(c1.hash(), c2.hash(), c4.hash());
        assertThatThrownBy(() -> s.deletion.delete(c3.hash(), DeletionMode.COMMIT))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(404));
    }
}
