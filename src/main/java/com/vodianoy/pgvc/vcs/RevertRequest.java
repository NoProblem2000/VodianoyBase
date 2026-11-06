package com.vodianoy.pgvc.vcs;

/**
 * {@code POST /api/vc/revert}: revert one commit of the history of {@code envId} with a new commit on top of HEAD.
 *
 * @param targetCommitHash commit to revert
 * @param message          blank: {@code Revert "<message of the target>"}
 * @param description      null: {@code Reverts commit <short hash>.}; blank: no description
 * @param customRevertSql  the compensating SQL as reviewed (and possibly edited) by the user; blank: DOWN SQL of the
 *                         target. When it differs from that, the DOWN SQL of the new commit is generated from the
 *                         schema difference instead of using the UP SQL of the target
 * @param allowDirty       run even if the database has uncommitted changes (they become part of the commit)
 * @param expectedHead     HEAD the UI was looking at; when set and HEAD moved since, the revert is refused
 */
public record RevertRequest(String envId, String targetCommitHash, String author, String message, String description,
                            String customRevertSql, boolean allowDirty, String lockTimeout, String statementTimeout,
                            String expectedHead) {

    public RevertRequest(String envId, String targetCommitHash, String author) {
        this(envId, targetCommitHash, author, null, null, null, false, null, null, null);
    }
}
