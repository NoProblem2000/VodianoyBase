package com.vodianoy.pgvc.vcs;

import com.vodianoy.pgvc.diff.ChangeItem;
import com.vodianoy.pgvc.safety.SqlSafetyReport;
import com.vodianoy.pgvc.storage.CommitNode;

import java.util.List;

/**
 * What {@code Revert Commit} would do (like {@code git revert}): run the DOWN SQL of one commit of the history on top
 * of the current HEAD and record it as a new commit, whose DOWN SQL is the UP SQL of the reverted commit.
 *
 * @param target             the commit to revert (in the history of HEAD, possibly HEAD itself)
 * @param targetIsHead       reverting HEAD: the same as a rollback by one commit, but recorded as a new commit
 * @param laterCommits       commits after the target up to HEAD
 * @param revertSql          the compensating SQL (DOWN SQL of the target), editable before it runs
 * @param revertDownSql      DOWN SQL of the new commit (UP SQL of the target)
 * @param defaultMessage     {@code Revert "<message of the target>"}
 * @param targetChanges      what the target commit changed (history wording)
 * @param restoredObjects    tables / partitions / columns the target dropped: the revert recreates their structure,
 *                           the rows deleted then do not come back
 * @param droppedObjects     tables / partitions / columns the target created: the revert drops them with their data
 * @param conflicts          objects the target changed that later commits changed again: the compensating SQL may
 *                           not fit the current schema any more
 * @param firstCommit        the target is the first commit of the history
 * @param safety             safety analysis of {@code revertSql} on the live database
 * @param dirty              the database has uncommitted changes (the revert refuses to run unless allowed)
 */
public record RevertPlan(String envId, String head, CommitNode target, boolean targetIsHead, int laterCommits,
                         String revertSql, String revertDownSql, String defaultMessage,
                         List<ChangeItem> targetChanges, List<String> restoredObjects, List<String> droppedObjects,
                         List<String> conflicts, boolean firstCommit, SqlSafetyReport safety, boolean dirty) {
}
