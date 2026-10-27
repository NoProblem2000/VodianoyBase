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

/**
 * Linking (baseline) an environment whose schema already exists to the history of another environment: the usual
 * situation when version control is introduced for existing Dev / Stage / Prod databases.
 */
@EnabledIfEnvironmentVariable(named = "PGVC_TEST_JDBC_URL", matches = ".+")
class LinkEnvironmentIntegrationTest {

    private static final String DEV_DB = "pgvc_step6_link_dev";
    private static final String PROD_DB = "pgvc_step6_link_prod";

    @TempDir
    Path dir;

    private Services s;
    private final String dev = envId(DEV_DB);
    private final String prod = envId(PROD_DB);

    @BeforeEach
    void setUp() throws Exception {
        recreate(DEV_DB);
        recreate(PROD_DB);
        // both databases already have the same schema before version control is introduced
        sql(DEV_DB, TestDatabases.fixture("rich-schema.sql"));
        sql(PROD_DB, TestDatabases.fixture("rich-schema.sql"));
        s = new Services(dir, DEV_DB, PROD_DB);
        s.storage.initializeEnvironment(dev);
    }

    @AfterEach
    void tearDown() {
        s.close();
    }

    private CommitNode baseline(String env) {
        return s.vc.commitWorkingTree(env, new CommitRequest("Baseline " + env, "alice", null, null, null)).commit();
    }

    private CommitNode execute(String env, String message, String sql) {
        return s.vc.executeAndCommitSql(env, new ExecuteMigrationRequest(message, "tester", sql, null, false)).commit();
    }

    /** The reported scenario: both environments initialized and committed separately, then one changes. */
    @Test
    void separatelyCommittedEnvironmentsAreLinkedAndThenPushed() throws Exception {
        CommitNode devBase = baseline(dev);
        s.storage.initializeEnvironment(prod);
        CommitNode prodBase = baseline(prod);
        CommitNode change = execute(dev, "Add notes", "CREATE TABLE sales.notes (id int PRIMARY KEY, body text);");

        EnvironmentComparison diverged = s.vc.compareEnvironments(dev, prod);
        assertThat(diverged.relation()).isEqualTo(HistoryRelation.DIVERGED);
        assertThat(diverged.commonAncestor()).isNull();
        // the newest dev commit with prod's schema is the baseline, not the newer change
        assertThat(diverged.matchingCommit()).isNotNull();
        assertThat(diverged.matchingCommit().hash()).isEqualTo(devBase.hash());

        LinkResult link = s.vc.linkEnvironmentToHistory(new LinkRequest(dev, prod, null, false, false));
        assertThat(link.previousHead()).isEqualTo(prodBase.hash());
        assertThat(link.newHead()).isEqualTo(devBase.hash());
        assertThat(link.linkedCommits()).extracting(CommitNode::hash).containsExactly(devBase.hash());
        assertThat(link.discardedCommits()).isEqualTo(1);
        assertThat(link.acceptedDifferences()).isEmpty();
        assertThat(s.storage.getCommitGraph(prod).commits()).extracting(CommitNode::hash).containsExactly(devBase.hash());
        assertThat(s.vc.getWorkingTreeStatus(prod).diff().isHasChanges()).isFalse();

        // now prod is simply behind and the change is pushed the normal way
        EnvironmentComparison behind = s.vc.compareEnvironments(dev, prod);
        assertThat(behind.relation()).isEqualTo(HistoryRelation.TARGET_BEHIND);
        assertThat(behind.matchingCommit()).isNull();
        assertThat(behind.commitsToPush()).extracting(CommitNode::hash).containsExactly(change.hash());
        PushResult push = s.vc.pushCommitsToEnvironment(dev, prod, null);
        assertThat(push.newHead()).isEqualTo(change.hash());
        assertThat(s.vc.compareEnvironments(dev, prod).relation()).isEqualTo(HistoryRelation.SAME);

        // the merged graph no longer shows prod's own baseline
        assertThat(s.vc.getGlobalGraph().commits()).extracting(GlobalGraph.Commit::hash)
                .containsExactlyInAnyOrder(devBase.hash(), change.hash());
    }

    @Test
    void notInitializedTargetIsInitializedAndLinkedToTheMatchingCommit() throws Exception {
        CommitNode devBase = baseline(dev);
        CommitNode change = execute(dev, "Add notes", "CREATE TABLE sales.notes (id int PRIMARY KEY, body text);");

        EnvironmentComparison fresh = s.vc.compareEnvironments(dev, prod);
        assertThat(fresh.relation()).isEqualTo(HistoryRelation.TARGET_NOT_INITIALIZED);
        assertThat(fresh.matchingCommit().hash()).isEqualTo(devBase.hash());

        LinkResult link = s.vc.linkEnvironmentToHistory(new LinkRequest(dev, prod, null, false, false));
        assertThat(link.previousHead()).isNull();
        assertThat(link.newHead()).isEqualTo(devBase.hash());
        assertThat(link.discardedCommits()).isZero();
        assertThat(s.storage.getStatus(prod).initialized()).isTrue();
        assertThat(s.storage.readAuditLog(prod)).isEmpty();
        assertThat(s.vc.compareEnvironments(dev, prod).commitsToPush()).extracting(CommitNode::hash)
                .containsExactly(change.hash());
    }

    @Test
    void schemaDifferencesBlockTheLinkUnlessAccepted() throws Exception {
        CommitNode devBase = baseline(dev);
        s.storage.initializeEnvironment(prod);
        CommitNode prodBase = baseline(prod);
        sql(PROD_DB, "ALTER TABLE sales.customers ADD COLUMN legacy_code text");

        EnvironmentComparison cmp = s.vc.compareEnvironments(dev, prod);
        assertThat(cmp.relation()).isEqualTo(HistoryRelation.DIVERGED);
        assertThat(cmp.matchingCommit()).isNull();

        // automatic choice: nothing matches
        assertThatThrownBy(() -> s.vc.linkEnvironmentToHistory(new LinkRequest(dev, prod, null, false, false)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).contains("does not match any commit"));
        // explicit commit: the differences are reported, nothing changes
        assertThatThrownBy(() -> s.vc.linkEnvironmentToHistory(new LinkRequest(dev, prod, devBase.hash(), false, false)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage())
                        .contains("differs from commit").contains("legacy_code").contains("Nothing was changed"));
        assertThat(s.storage.getHead(prod)).contains(prodBase.hash());
        assertThat(s.storage.getCommitGraph(prod).commits()).hasSize(1);

        // accepted on purpose, prod's own commits kept
        LinkResult link = s.vc.linkEnvironmentToHistory(new LinkRequest(dev, prod, devBase.hash(), true, true));
        assertThat(link.newHead()).isEqualTo(devBase.hash());
        assertThat(link.discardedCommits()).isZero();
        assertThat(link.acceptedDifferences()).extracting(c -> c.getObjectName()).anyMatch(n -> n.contains("legacy_code"));
        assertThat(s.storage.getCommitGraph(prod).commits()).extracting(CommitNode::hash)
                .containsExactlyInAnyOrder(devBase.hash(), prodBase.hash());
        // the accepted difference stays visible as an uncommitted change of prod
        assertThat(s.vc.getWorkingTreeStatus(prod).diff().isHasChanges()).isTrue();
    }

    @Test
    void linkToAnOlderCommitOfTheHistory() throws Exception {
        CommitNode devBase = baseline(dev);
        CommitNode c2 = execute(dev, "Add notes", "CREATE TABLE sales.notes (id int PRIMARY KEY, body text);");
        CommitNode c3 = execute(dev, "Notes index", "CREATE INDEX notes_body_idx ON sales.notes (body);");
        sql(PROD_DB, "CREATE TABLE sales.notes (id int PRIMARY KEY, body text)");

        assertThat(s.vc.compareEnvironments(dev, prod).matchingCommit().hash()).isEqualTo(c2.hash());
        LinkResult link = s.vc.linkEnvironmentToHistory(new LinkRequest(dev, prod, c2.hash(), false, false));
        assertThat(link.linkedCommits()).extracting(CommitNode::hash).containsExactly(devBase.hash(), c2.hash());
        assertThat(s.vc.compareEnvironments(dev, prod).commitsToPush()).extracting(CommitNode::hash)
                .containsExactly(c3.hash());

        // already there / unknown commits are rejected
        assertThatThrownBy(() -> s.vc.linkEnvironmentToHistory(new LinkRequest(dev, prod, c2.hash(), false, false)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).contains("already at"));
        assertThatThrownBy(() -> s.vc.linkEnvironmentToHistory(new LinkRequest(dev, prod, "deadbeef", false, false)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void sourceWithoutCommitsCannotBeLinkedTo() {
        assertThatThrownBy(() -> s.vc.linkEnvironmentToHistory(new LinkRequest(dev, prod, null, false, false)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).contains("no commits"));
        assertThatThrownBy(() -> s.vc.linkEnvironmentToHistory(new LinkRequest(dev, dev, null, false, false)))
                .isInstanceOf(ApiException.class);
    }
}
