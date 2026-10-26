package com.vodianoy.pgvc.vcs;

import com.vodianoy.pgvc.storage.CommitNode;

import java.util.List;

/**
 * What a rollback to {@code targetHash} would execute.
 *
 * @param commitsToRevert commits after the target, newest first (their down_sql runs in this order)
 * @param rollbackSql     the combined down_sql, ready to be edited and passed as customRollbackSql
 */
public record RollbackPlan(String head, String targetHash, List<CommitNode> commitsToRevert, String rollbackSql) {
}
