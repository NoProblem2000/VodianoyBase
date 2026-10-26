package com.vodianoy.pgvc.vcs;

/** How the history of a target environment relates to the history of a source environment. */
public enum HistoryRelation {
    /** Both are at the same commit. */
    SAME,
    /** The target HEAD is an ancestor of the source HEAD: the missing commits can be pushed. */
    TARGET_BEHIND,
    /** The source HEAD is an ancestor of the target HEAD. */
    TARGET_AHEAD,
    /** Both moved on from a common ancestor (or have unrelated histories). */
    DIVERGED,
    /** _pgvc exists in the target but it has no commits yet. */
    TARGET_EMPTY,
    /** The target has no _pgvc schema yet (push initializes it). */
    TARGET_NOT_INITIALIZED
}
