package com.vodianoy.pgvc.environment;

/**
 * A user schema of a database (pg_namespace without pg_catalog, information_schema, pg_toast*, pg_temp* and _pgvc).
 *
 * @param comment     COMMENT ON SCHEMA, null when there is none
 * @param tableCount  number of tables, partitions, views and sequences in it
 * @param extension   the schema was created by an extension (not version controlled)
 */
public record DiscoveredSchema(String name, String comment, String owner, long tableCount, boolean extension) {
}
