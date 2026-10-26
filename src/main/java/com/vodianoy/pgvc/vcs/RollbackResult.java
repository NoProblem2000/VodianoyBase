package com.vodianoy.pgvc.vcs;

import com.vodianoy.pgvc.storage.CommitNode;

import java.util.List;

/** Outcome of a rollback: HEAD moved from {@code previousHead} to {@code newHead}. */
public record RollbackResult(String previousHead, String newHead, List<CommitNode> revertedCommits, String executedSql,
                             boolean verified) {
}
