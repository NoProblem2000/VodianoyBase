package com.vodianoy.pgvc.vcs;

import com.vodianoy.pgvc.diff.ChangeItem;
import com.vodianoy.pgvc.storage.CommitNode;

import java.util.List;

/** A new commit and the schema changes it records. */
public record CommitResult(CommitNode commit, List<ChangeItem> changes) {
}
