package com.vodianoy.pgvc.safety;

/**
 * @param index 1-based position of the statement in the script
 * @param step  1-based step it belongs to
 */
public record StatementSafety(int index, int step, String sql, SafetyReport report) {
}
