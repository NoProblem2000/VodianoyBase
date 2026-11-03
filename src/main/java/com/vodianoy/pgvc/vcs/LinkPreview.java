package com.vodianoy.pgvc.vcs;

import com.vodianoy.pgvc.diff.SchemaDiffResult;
import com.vodianoy.pgvc.storage.CommitNode;

/**
 * What linking the target environment to a commit of the source would leave behind, before anything is done.
 *
 * <p>{@code differences} is written from the point of view of the target: what must change in its live schema so
 * that it equals the commit ({@code upSql} = live target -&gt; commit, e.g. {@code ADD COLUMN} for a column the target
 * lacks). After a link with differences they stay as uncommitted changes of the target: <b>Discard</b> runs exactly
 * that SQL, <b>Commit</b> records the current schema of the target instead.
 *
 * @param commit            the commit of the source the target would be linked to
 * @param identical         the live schema of the target equals the commit snapshot
 * @param targetInitialized the target already has the _pgvc schema
 */
public record LinkPreview(String sourceEnvironmentId, String targetEnvironmentId, CommitNode commit, boolean identical,
                          boolean targetInitialized, SchemaDiffResult differences) {
}
