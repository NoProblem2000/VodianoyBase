package com.vodianoy.pgvc.snapshot;

/**
 * Apply the reviewed migration to {@code envId} and record it as a commit there.
 *
 * @param message          blank: "Sync with external snapshot [&lt;short sourceCommitHash&gt;]"
 * @param sourceCommitHash commit of the reference snapshot (for the default message)
 * @param lockTimeout      blank: {@code pgvc.lock-timeout}
 * @param statementTimeout blank: {@code pgvc.statement-timeout}
 * @param preScript        runs before {@code upSql}
 * @param postScript       runs after {@code upSql}
 * @param dryRun           run in a rolled-back transaction and return a dry-run report instead of committing
 * @param description      optional longer description of the commit
 */
public record SnapshotApplyRequest(String envId, String upSql, String downSql, String author, String message,
                                   String sourceCommitHash, String lockTimeout, String statementTimeout,
                                   String preScript, String postScript, boolean dryRun, String description) {

    public SnapshotApplyRequest(String envId, String upSql, String downSql, String author, String message,
                                String sourceCommitHash) {
        this(envId, upSql, downSql, author, message, sourceCommitHash, null, null, null, null, false, null);
    }

    public SnapshotApplyRequest(String envId, String upSql, String downSql, String author, String message,
                                String sourceCommitHash, String lockTimeout, String statementTimeout,
                                String preScript, String postScript, boolean dryRun) {
        this(envId, upSql, downSql, author, message, sourceCommitHash, lockTimeout, statementTimeout, preScript,
                postScript, dryRun, null);
    }
}
