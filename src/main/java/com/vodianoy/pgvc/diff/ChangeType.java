package com.vodianoy.pgvc.diff;

/** Kind of a schema change between two snapshots. */
public enum ChangeType {
    ADDED,
    MODIFIED,
    RENAMED,
    DELETED;

    /** The change the reverse migration performs. */
    ChangeType inverse() {
        return switch (this) {
            case ADDED -> DELETED;
            case DELETED -> ADDED;
            default -> this;
        };
    }
}
