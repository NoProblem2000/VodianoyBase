package com.vodianoy.pgvc.vcs;

/**
 * Online push of commits from one environment to another.
 *
 * @param targetCommitHash commit of the source to bring the target to; blank = the source HEAD
 * @param allowDirty       push even if the target has uncommitted schema changes
 * @param skipVerification do not compare the resulting target schema with the snapshot of the last commit
 * @param lockTimeout      blank: {@code pgvc.lock-timeout}
 * @param statementTimeout blank: {@code pgvc.statement-timeout}
 */
public record PushRequest(String sourceEnvironmentId, String targetEnvironmentId, String targetCommitHash,
                          boolean allowDirty, boolean skipVerification, String lockTimeout, String statementTimeout) {

    public PushRequest(String sourceEnvironmentId, String targetEnvironmentId, String targetCommitHash,
                       boolean allowDirty, boolean skipVerification) {
        this(sourceEnvironmentId, targetEnvironmentId, targetCommitHash, allowDirty, skipVerification, null, null);
    }
}
