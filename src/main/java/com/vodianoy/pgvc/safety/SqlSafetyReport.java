package com.vodianoy.pgvc.safety;

import java.util.List;

/**
 * Safety of an arbitrary script, statement by statement.
 *
 * @param overall   the worst of all statements (lock, flags) with the summed duration estimate
 * @param steps     number of steps ({@code -- pgvc:step})
 * @param multiStep the script runs as several transactions (markers or CONCURRENTLY statements)
 */
public record SqlSafetyReport(SafetyReport overall, List<StatementSafety> statements, int steps, boolean multiStep) {
}
