package com.vodianoy.pgvc.core.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Ordinary or partitioned table (pg_class.relkind IN ('r', 'p')).
 *
 * @param oid            pg_class.oid (stable across RENAME TABLE, used for rename detection)
 * @param schema         schema name
 * @param name           table name
 * @param partitioned    relkind = 'p'
 * @param partitionKey   pg_get_partkeydef(oid), e.g. {@code RANGE (created_at)}
 * @param parentSchema   schema of the partitioned parent (for partitions)
 * @param parentTable    name of the partitioned parent (for partitions)
 * @param partitionBound pg_get_expr(relpartbound, oid), e.g. {@code FOR VALUES FROM ('2026-01-01') TO ('2026-02-01')}
 * @param columns        columns ordered by attnum
 * @param constraints    local constraints
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TableDef(long oid, String schema, String name, boolean partitioned, String partitionKey,
                       String parentSchema, String parentTable, String partitionBound,
                       List<ColumnDef> columns, List<ConstraintDef> constraints) {

    public TableDef {
        columns = columns == null ? List.of() : List.copyOf(columns);
        constraints = constraints == null ? List.of() : List.copyOf(constraints);
    }

    @JsonIgnore
    public String key() {
        return schema + "." + name;
    }

    @JsonIgnore
    public boolean partition() {
        return parentTable != null;
    }

    @JsonIgnore
    public String parentKey() {
        return parentTable == null ? null : parentSchema + "." + parentTable;
    }
}
