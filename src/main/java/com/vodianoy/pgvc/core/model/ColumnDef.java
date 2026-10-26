package com.vodianoy.pgvc.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Column of a table, read from pg_attribute.
 *
 * @param attnum      pg_attribute.attnum (stable across RENAME COLUMN, used for rename detection)
 * @param name        column name
 * @param type        format_type(atttypid, atttypmod)
 * @param notNull     attnotnull
 * @param defaultExpr pg_get_expr(adbin, adrelid) or null
 * @param identity    attidentity: "a" (ALWAYS), "d" (BY DEFAULT) or null
 * @param local       attislocal (false for columns inherited from a partitioned parent)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ColumnDef(int attnum, String name, String type, boolean notNull, String defaultExpr,
                        String identity, boolean local) {
}
