package com.vodianoy.pgvc.model.schema;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/** Ordinary table, partitioned table or partition (pg_class.relkind IN ('r', 'p')). */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class TableModel {

    /** pg_class.oid: survives ALTER TABLE ... RENAME, so it identifies the table across renames. */
    private long oid;

    private String schemaName;

    private String name;

    /** relkind = 'p'. */
    private boolean partitioned;

    /** pg_get_partkeydef(oid) of a partitioned table, e.g. {@code RANGE (created_on)}. */
    private String partitionKeyDef;

    /** Partitioned parent of a partition as {@code schema.table} (pg_inherits), otherwise null. */
    private String parentTable;

    /** pg_get_expr(relpartbound, oid) of a partition, e.g. {@code FOR VALUES FROM ('2026-01-01') TO ('2026-02-01')}. */
    private String partitionBoundExpr;

    /** Columns by name, in attnum order. */
    @Builder.Default
    private Map<String, ColumnModel> columns = new LinkedHashMap<>();

    /** Constraints defined on this table by name (constraints inherited from a partitioned parent are omitted). */
    @Builder.Default
    private Map<String, ConstraintModel> constraints = new LinkedHashMap<>();

    /** Standalone indexes by name (indexes backing PK / UNIQUE / EXCLUDE constraints are omitted). */
    @Builder.Default
    private Map<String, IndexModel> indexes = new LinkedHashMap<>();

    @JsonIgnore
    public String getQualifiedName() {
        return DatabaseSnapshot.key(schemaName, name);
    }

    /** True for a partition of a partitioned table. */
    @JsonIgnore
    public boolean isPartition() {
        return parentTable != null;
    }
}
