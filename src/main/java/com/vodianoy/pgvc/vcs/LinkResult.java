package com.vodianoy.pgvc.vcs;

import com.vodianoy.pgvc.diff.ChangeItem;
import com.vodianoy.pgvc.storage.CommitNode;

import java.util.List;

/**
 * Outcome of linking a target environment to the history of a source environment.
 *
 * @param linkedCommits      source commits written to the target history, oldest first
 * @param discardedCommits   number of the target's own previous commits that were deleted
 * @param acceptedDifferences differences between the live target schema and the commit snapshot that were accepted
 *                           with {@code allowSchemaDifferences} (empty when the schemas matched)
 */
public record LinkResult(String sourceEnvironmentId, String targetEnvironmentId, String previousHead, String newHead,
                         List<CommitNode> linkedCommits, int discardedCommits, List<ChangeItem> acceptedDifferences) {
}
