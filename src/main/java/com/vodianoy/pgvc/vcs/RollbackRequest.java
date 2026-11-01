package com.vodianoy.pgvc.vcs;

/**
 * Rollback of the database to an earlier commit.
 *
 * @param targetHash        commit to return to (an ancestor of HEAD)
 * @param customRollbackSql SQL to execute instead of the generated chain of down_sql (e.g. edited in the UI)
 * @param allowDirty        roll back even if the database has uncommitted schema changes
 * @param skipVerification  do not compare the resulting schema with the snapshot of the target commit
 * @param lockTimeout       blank: {@code pgvc.lock-timeout}
 * @param statementTimeout  blank: {@code pgvc.statement-timeout}
 */
public record RollbackRequest(String targetHash, String customRollbackSql, boolean allowDirty, boolean skipVerification,
                              String lockTimeout, String statementTimeout) {

    public RollbackRequest(String targetHash, String customRollbackSql, boolean allowDirty, boolean skipVerification) {
        this(targetHash, customRollbackSql, allowDirty, skipVerification, null, null);
    }
}
