package com.vodianoy.pgvc.diff;

/**
 * What a comparison describes, for the wording of its changes and warnings (above all data loss).
 *
 * <ul>
 *   <li>{@link #APPLIED}: the changes already happened in the live database ("to" is live: working tree, drift) - a
 *       dropped table's rows are already gone, discarding only recreates an empty structure.</li>
 *   <li>{@link #PLANNED}: the changes happen when the script runs (sync / push, snapshot, rollback, revert, dry-run).</li>
 *   <li>{@link #RECORDED}: what a commit did (history, the result of an executed migration).</li>
 * </ul>
 * The reverse script of an applied or planned comparison is always planned (it is what Discard / a rollback runs).
 * SQL comments in the scripts always use the planned wording: a script describes what running it does.
 */
public enum DiffMode {
    APPLIED,
    PLANNED,
    RECORDED;

    /** Mode of the reverse comparison (DOWN SQL). */
    DiffMode reverse() {
        return this == RECORDED ? RECORDED : PLANNED;
    }
}
