package com.vodianoy.pgvc.vcs;

/**
 * @param full                all uncommitted changes were discarded (and verified unless skipped)
 * @param discardedChanges    how many changes were reverted
 * @param remainingChanges    uncommitted changes left afterwards
 * @param executedSql         the SQL that ran
 * @param auditEntriesRemoved DDL entries removed from _pgvc.ddl_audit_log (all on a full reset; on a partial one only
 *                            the entries the discard itself produced)
 * @param verified            the schema was checked against HEAD afterwards
 */
public record DiscardResult(String envId, String head, boolean full, int discardedChanges, int remainingChanges,
                            String executedSql, int auditEntriesRemoved, boolean verified) {
}
