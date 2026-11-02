package com.vodianoy.pgvc.model.schema;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.List;
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

    /**
     * Estimated number of rows ({@code pg_class.reltuples}; 0 when never analyzed). For a partitioned table: the sum
     * over its partitions. Statistics only: never compared by the diff engine.
     */
    private long estimatedRows;

    /** {@code pg_total_relation_size}: table, indexes and TOAST in bytes (partitioned table: sum of its partitions). */
    private long totalSizeBytes;

    /** {@code pg_size_pretty(pg_total_relation_size)}, e.g. {@code 1234 MB}. */
    private String prettySize;

    /** COMMENT ON TABLE, null when there is none. */
    private String comment;

    /** Owner role; null when owners are not tracked (ignore rules or an older snapshot). */
    private String owner;

    /**
     * Privileges granted to other roles than the owner: {@code grantee=PRIVILEGE} or {@code grantee=PRIVILEGE*} (with
     * grant option), grantee quoted when needed, {@code PUBLIC} for everyone. Null when grants are not tracked.
     */
    private List<String> acl;

    /**
     * Tablespace of the table (the database default tablespace when none is set); null when tablespaces are not
     * tracked.
     */
    private String tablespace;

    /** Columns by name, in attnum order. */
    @Builder.Default
    private Map<String, ColumnModel> columns = new LinkedHashMap<>();

    /** Constraints defined on this table by name (constraints inherited from a partitioned parent are omitted). */
    @Builder.Default
    private Map<String, ConstraintModel> constraints = new LinkedHashMap<>();

    /** Standalone indexes by name (indexes backing PK / UNIQUE / EXCLUDE constraints are omitted). */
    @Builder.Default
    private Map<String, IndexModel> indexes = new LinkedHashMap<>();

    /** Rows of the table when it is tracked as reference data (a dictionary), otherwise null. */
    private ReferenceDataModel referenceData;

    /** User triggers by name. */
    @Builder.Default
    private Map<String, TriggerModel> triggers = new LinkedHashMap<>();

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
