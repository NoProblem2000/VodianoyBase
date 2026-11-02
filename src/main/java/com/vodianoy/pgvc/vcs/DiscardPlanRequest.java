package com.vodianoy.pgvc.vcs;

import java.util.List;

/**
 * @param envId         environment with uncommitted changes
 * @param changeIndexes positions in the working-tree change list (WorkingTreeStatus.diff.changes) to discard;
 *                      null or empty: all of them (reset to HEAD)
 */
public record DiscardPlanRequest(String envId, List<Integer> changeIndexes) {
}
