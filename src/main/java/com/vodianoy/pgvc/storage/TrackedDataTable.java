package com.vodianoy.pgvc.storage;

import java.time.OffsetDateTime;

/** Row of _pgvc.tracked_data_tables: a table whose rows are version controlled. */
public record TrackedDataTable(String schemaName, String tableName, String trackedBy, OffsetDateTime trackedAt) {

    /** {@code schema.table}, the key of the table in a snapshot. */
    public String key() {
        return schemaName + "." + tableName;
    }
}
