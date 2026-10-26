package com.vodianoy.pgvc.vcs;

import com.vodianoy.pgvc.diff.SchemaDiffResult;
import com.vodianoy.pgvc.storage.CommitNode;

import java.util.List;

/**
 * Source vs target environment.
 *
 * @param commitsToPush               commits a push would apply to the target, oldest first (empty unless the target is
 *                                    behind, empty or not initialized)
 * @param commonAncestor              newest commit both histories share (for diverged histories)
 * @param targetHasUncommittedChanges the target schema differs from its own HEAD
 * @param schemaDiff                  live target schema -> schema of the source HEAD
 */
public record EnvironmentComparison(String sourceEnvironmentId, String targetEnvironmentId, String sourceHead,
                                    String targetHead, HistoryRelation relation, String commonAncestor,
                                    List<CommitNode> commitsToPush, boolean targetHasUncommittedChanges,
                                    SchemaDiffResult schemaDiff) {
}
