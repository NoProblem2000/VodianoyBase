package com.vodianoy.pgvc.vcs;

import com.vodianoy.pgvc.diff.ChangeItem;
import com.vodianoy.pgvc.execution.StepReport;
import com.vodianoy.pgvc.storage.CommitNode;

import java.util.List;

/**
 * A new commit and the schema changes it records.
 *
 * @param steps     how the executed steps went (Mode B; empty for a working-tree commit)
 * @param elapsedMs execution time of the migration SQL
 */
public record CommitResult(CommitNode commit, List<ChangeItem> changes, List<StepReport> steps, long elapsedMs) {

    public CommitResult(CommitNode commit, List<ChangeItem> changes) {
        this(commit, changes, List.of(), 0);
    }
}
