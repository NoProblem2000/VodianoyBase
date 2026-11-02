package com.vodianoy.pgvc.vcs;

import com.vodianoy.pgvc.diff.ChangeItem;
import com.vodianoy.pgvc.safety.SqlSafetyReport;

import java.util.List;

/**
 * What discarding uncommitted changes would execute.
 *
 * @param head         HEAD the database returns to; null when there are no commits yet (everything uncommitted is
 *                     dropped: the database returns to an empty schema)
 * @param full         all uncommitted changes are discarded (reset to HEAD)
 * @param totalChanges number of uncommitted changes (sent back with the request: the plan is refused when it changed)
 * @param changes      the changes that are discarded
 * @param downSql      SQL that reverts them, in a dependency-safe order (editable before it runs)
 * @param safety       risk of {@code downSql} (data loss, locks)
 * @param auditEntries DDL entries in _pgvc.ddl_audit_log
 */
public record DiscardPlan(String envId, String head, boolean full, int totalChanges, List<ChangeItem> changes,
                          String downSql, SqlSafetyReport safety, int auditEntries) {
}
