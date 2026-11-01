package com.vodianoy.pgvc.execution;

/**
 * How one step went.
 *
 * @param index         1-based
 * @param transactional false: the statements ran one by one outside of a transaction
 * @param elapsedMs     wall-clock time of the step
 */
public record StepReport(int index, boolean transactional, int statements, long elapsedMs) {
}
