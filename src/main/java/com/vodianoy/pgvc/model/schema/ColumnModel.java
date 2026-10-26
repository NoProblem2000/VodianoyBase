package com.vodianoy.pgvc.model.schema;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Column from pg_attribute (attnum > 0 and not dropped). */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ColumnModel {

    /** pg_attribute.attnum: survives RENAME COLUMN, so it identifies the column across renames. */
    private int attnum;

    private String name;

    /** format_type(atttypid, atttypmod), e.g. {@code numeric(12,2)}, {@code character varying(100)}. */
    private String dataType;

    /** NOT attnotnull. */
    private boolean nullable;

    /** pg_get_expr(adbin, adrelid) of a DEFAULT, e.g. {@code now()}; null when there is none. */
    private String defaultValue;

    /** {@code ALWAYS} / {@code BY DEFAULT} for identity columns, otherwise null. */
    private String identityGeneration;

    /** Expression of a {@code GENERATED ALWAYS AS (...) STORED} column (PostgreSQL 12+), otherwise null. */
    private String generatedExpression;

    /** Collation (already quoted) when it differs from the type's default collation, otherwise null. */
    private String collation;

    /** The column comes from the partitioned parent (attislocal = false). */
    private boolean inherited;
}
