package com.vodianoy.pgvc.vcs;

import com.vodianoy.pgvc.common.ApiException;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Which commits a deletion removes and what blocks it, on a synthetic graph (no database). */
class CommitDeletionPlanTest {

    /*
     *        e   f          dev HEAD = d, prod HEAD = b
     *         \ /
     *    d     x            x, e, f, y: abandoned (no HEAD on them)
     *    |     |
     *    c     y
     *     \   /
     *       b
     *       |
     *       a
     */
    private final List<GlobalGraph.Commit> commits = new ArrayList<>();
    private int minute;

    private void commit(String hash, String parent) {
        commits.add(new GlobalGraph.Commit(hash, parent, "t", "msg " + hash,
                OffsetDateTime.of(2026, 1, 1, 0, minute++, 0, 0, ZoneOffset.UTC), List.of("dev")));
    }

    private GlobalGraph graph(boolean prodReachable) {
        commits.clear();
        minute = 0;
        commit("a", null);
        commit("b", "a");
        commit("c", "b");
        commit("y", "b");
        commit("d", "c");
        commit("x", "y");
        commit("e", "x");
        commit("f", "x");
        return new GlobalGraph(commits, List.of(
                new GlobalGraph.EnvironmentHead("dev", "Dev", "#22c55e", true, true, true, "d", null),
                new GlobalGraph.EnvironmentHead("prod", "Prod", "#ef4444", false, prodReachable, true,
                        prodReachable ? "b" : null, prodReachable ? null : "timeout")));
    }

    private static List<String> hashes(DeletionPlan plan) {
        return plan.commits().stream().map(GlobalGraph.Commit::hash).toList();
    }

    @Test
    void commitModeTakesTheCommitAndItsDescendants() {
        DeletionPlan tip = CommitDeletionService.plan(graph(true), "e", DeletionMode.COMMIT);
        assertThat(hashes(tip)).containsExactly("e");
        assertThat(tip.deletable()).isTrue();
        assertThat(tip.environments()).containsExactly("dev");

        // x has children e and f: they go too, newest first
        assertThat(hashes(CommitDeletionService.plan(graph(true), "x", DeletionMode.COMMIT)))
                .containsExactly("f", "e", "x");
    }

    @Test
    void branchModeStopsAtTheForkPoint() {
        // e's parent x has another child f: the branch is just e
        assertThat(hashes(CommitDeletionService.plan(graph(true), "e", DeletionMode.BRANCH))).containsExactly("e");
        // from x: x, its children, and y; b is used by both environments
        assertThat(hashes(CommitDeletionService.plan(graph(true), "x", DeletionMode.BRANCH)))
                .containsExactly("f", "e", "x", "y");
        assertThat(hashes(CommitDeletionService.plan(graph(true), "y", DeletionMode.BRANCH)))
                .containsExactly("f", "e", "x", "y");
    }

    @Test
    void commitsOfALiveHistoryAreBlocked() {
        DeletionPlan headOfDev = CommitDeletionService.plan(graph(true), "d", DeletionMode.COMMIT);
        assertThat(headOfDev.deletable()).isFalse();
        assertThat(headOfDev.blockedBy()).containsExactly("Dev");

        DeletionPlan shared = CommitDeletionService.plan(graph(true), "b", DeletionMode.COMMIT);
        assertThat(shared.blockedBy()).containsExactly("Dev", "Prod");

        DeletionPlan c = CommitDeletionService.plan(graph(true), "c", DeletionMode.BRANCH);
        assertThat(c.blockedBy()).containsExactly("Dev");
    }

    @Test
    void anUnreachableEnvironmentBlocksEverything() {
        DeletionPlan plan = CommitDeletionService.plan(graph(false), "e", DeletionMode.COMMIT);
        assertThat(plan.deletable()).isFalse();
        assertThat(plan.blockedBy()).isEmpty();
        assertThat(plan.offlineEnvironments()).containsExactly("Prod");
    }

    @Test
    void unknownCommit() {
        assertThatThrownBy(() -> CommitDeletionService.plan(graph(true), "zzz", DeletionMode.COMMIT))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).contains("not found"));
    }
}
