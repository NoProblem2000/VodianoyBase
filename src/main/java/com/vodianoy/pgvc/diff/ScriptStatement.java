package com.vodianoy.pgvc.diff;

/**
 * One statement of a generated migration in execution order, tied to the change it belongs to.
 * Dropping the statements of unselected changes keeps the dependency-safe order of the rest.
 *
 * @param changeId {@link ChangeItem#getId()} of the change, or -1 for a statement that belongs to no single change
 */
public record ScriptStatement(int changeId, String sql) {
}
