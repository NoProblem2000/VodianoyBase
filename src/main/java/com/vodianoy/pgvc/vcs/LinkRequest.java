package com.vodianoy.pgvc.vcs;

/**
 * Links an environment whose schema already exists to the history of another environment (a "baseline"): the target
 * gets the source commits up to {@code commitHash} and its HEAD is set to that commit. No migration SQL is executed.
 *
 * @param commitHash             commit of the source the target schema corresponds to; blank = the newest source commit
 *                               whose snapshot equals the live target schema
 * @param allowSchemaDifferences link even if the live target schema differs from the snapshot of the commit
 *                               (e.g. view text formatted differently by another PostgreSQL major version)
 * @param keepTargetCommits      keep the target's own previous commits in its _pgvc.commits (they are no longer on
 *                               its history); by default they are deleted
 */
public record LinkRequest(String sourceEnvironmentId, String targetEnvironmentId, String commitHash,
                          boolean allowSchemaDifferences, boolean keepTargetCommits) {
}
