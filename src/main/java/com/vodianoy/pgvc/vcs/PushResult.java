package com.vodianoy.pgvc.vcs;

import com.vodianoy.pgvc.storage.CommitNode;

import java.util.List;

/** Outcome of a push: the target moved from {@code previousHead} to {@code newHead}. */
public record PushResult(String sourceEnvironmentId, String targetEnvironmentId, String previousHead, String newHead,
                         List<CommitNode> appliedCommits, boolean verified) {
}
