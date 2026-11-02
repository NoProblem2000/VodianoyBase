package com.vodianoy.pgvc.refdata;

import java.time.OffsetDateTime;

/**
 * A table of an environment in the "Reference Data Tables" list.
 *
 * @param key            {@code schema.table}
 * @param tracked        its rows are version controlled
 * @param hasPrimaryKey  required for tracking: rows are matched by their primary key
 * @param estimatedRows  planner estimate (pg_class.reltuples)
 * @param rowCount       tracked tables: rows read with the last snapshot (at most the limit + 1)
 * @param overLimit      tracked tables: more than {@code maxRows} rows, the rows are not compared any more
 * @param eligible       can be tracked: has a primary key and is estimated at no more than {@code maxRows} rows
 * @param maxRows        the row limit of reference data
 */
public record ReferenceDataTableView(String key, String schemaName, String tableName, boolean tracked,
                                     boolean hasPrimaryKey, long estimatedRows, Long rowCount, boolean overLimit,
                                     boolean eligible, int columns, String trackedBy, OffsetDateTime trackedAt,
                                     int maxRows) {
}
