package com.vodianoy.pgvc.snapshot;

/**
 * Apply the reviewed migration to {@code envId} and record it as a commit there.
 *
 * @param message          blank: "Sync with external snapshot [&lt;short sourceCommitHash&gt;]"
 * @param sourceCommitHash commit of the reference snapshot (for the default message)
 */
public record SnapshotApplyRequest(String envId, String upSql, String downSql, String author, String message,
                                   String sourceCommitHash) {
}
