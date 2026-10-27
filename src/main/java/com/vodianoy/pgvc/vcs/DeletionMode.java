package com.vodianoy.pgvc.vcs;

/** What a deletion removes besides the selected commit. */
public enum DeletionMode {
    /** the commit and every commit made on top of it (a child cannot exist without its parent) */
    COMMIT,
    /** as COMMIT, plus the earlier commits of the same abandoned branch down to where it forked off a live history */
    BRANCH
}
