package com.vodianoy.pgvc.vcs;

/**
 * Online push of commits from one environment to another.
 *
 * @param targetCommitHash commit of the source to bring the target to; blank = the source HEAD
 * @param allowDirty       push even if the target has uncommitted schema changes
 * @param skipVerification do not compare the resulting target schema with the snapshot of the last commit
 */
public record PushRequest(String sourceEnvironmentId, String targetEnvironmentId, String targetCommitHash,
                          boolean allowDirty, boolean skipVerification) {
}
