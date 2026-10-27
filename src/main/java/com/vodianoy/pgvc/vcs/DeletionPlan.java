package com.vodianoy.pgvc.vcs;

import java.util.List;

/**
 * What deleting a commit would remove.
 *
 * @param commits              commits that would be deleted, newest first
 * @param environments         ids of the environments whose _pgvc.commits contain any of them
 * @param blockedBy            names of environments whose current history (HEAD and its ancestors) contains any of the
 *                             commits: they must be rolled back first
 * @param offlineEnvironments  names of unreachable environments: it is unknown whether they use the commits
 * @param deletable            true when nothing blocks the deletion
 */
public record DeletionPlan(String hash, DeletionMode mode, List<GlobalGraph.Commit> commits, List<String> environments,
                           List<String> blockedBy, List<String> offlineEnvironments, boolean deletable) {
}
