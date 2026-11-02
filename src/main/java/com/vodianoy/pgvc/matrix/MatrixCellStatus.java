package com.vodianoy.pgvc.matrix;

/** State of one object in one database, relative to the baseline database. */
public enum MatrixCellStatus {
    /** Same as in the baseline (or: this is the baseline and it has the object). */
    IDENTICAL,
    /** Exists in both, but differs (columns, definition, comment, grants ...). */
    MODIFIED,
    /** In the baseline, not in this database. */
    MISSING,
    /** Only in this database, not in the baseline. */
    EXTRA_ONLY_HERE,
    /** Neither here nor in the baseline (the object exists only in other databases). */
    ABSENT,
    /** The database could not be read. */
    UNAVAILABLE
}
