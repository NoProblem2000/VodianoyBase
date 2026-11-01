package com.vodianoy.pgvc.vcs;

import com.vodianoy.pgvc.diff.ChangeItem;
import com.vodianoy.pgvc.execution.StepReport;
import com.vodianoy.pgvc.execution.Timeouts;
import com.vodianoy.pgvc.safety.SqlSafetyReport;

import java.util.List;

/**
 * Outcome of a dry-run: the script ran on the real database inside one transaction that was rolled back.
 *
 * @param success             every statement succeeded
 * @param elapsedMs           real execution time of the script (introspection excluded)
 * @param steps               the steps that ran (all inside the one test transaction)
 * @param totalSteps          steps of the script
 * @param failedStep          1-based step that failed, or null
 * @param error               localized error, or null
 * @param changes             schema changes the script made (before the rollback)
 * @param generatedDownSql    DOWN SQL that would be stored when none is given
 * @param rewrittenStatements statements that cannot run inside a transaction and ran in a modified form (CONCURRENTLY
 *                            removed, VACUUM skipped): their real execution happens in a step of its own
 * @param uncommittedChanges  number of schema changes in the database that are not committed yet
 * @param safety              safety analysis of the script against the database
 * @param timeouts            the timeouts that were in force
 */
public record DryRunResult(boolean success, long elapsedMs, List<StepReport> steps, int totalSteps, Integer failedStep,
                           String error, List<ChangeItem> changes, String generatedDownSql,
                           List<String> rewrittenStatements, int uncommittedChanges, SqlSafetyReport safety,
                           Timeouts timeouts) {
}
