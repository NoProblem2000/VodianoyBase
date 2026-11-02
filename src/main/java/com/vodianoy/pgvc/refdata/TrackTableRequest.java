package com.vodianoy.pgvc.refdata;

/**
 * @param table  {@code schema.table}
 * @param author who marks the table (stored in _pgvc.tracked_data_tables)
 */
public record TrackTableRequest(String table, String author) {
}
