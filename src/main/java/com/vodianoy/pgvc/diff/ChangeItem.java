package com.vodianoy.pgvc.diff;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One detected change, e.g. "column sales.orders.amount: numeric(10,2) -> numeric(12,2)".
 * {@link #upSql} applies the change, {@link #downSql} reverts it. The executable, dependency-ordered scripts are
 * {@link SchemaDiffResult#getUpSql()} / {@link SchemaDiffResult#getDownSql()}; these per-change fragments are for
 * reviewing what each change does.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChangeItem {

    /** Position in {@link SchemaDiffResult#getChanges()}; set by {@link SchemaDiffEngine#compare} only. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer id;

    private ChangeType changeType;

    private ObjectType objectType;

    /** Qualified name in the new state, e.g. {@code sales.orders}, {@code sales.orders.amount}. */
    private String objectName;

    /**
     * Top-level object the change belongs to (columns, constraints, indexes, triggers and comments belong to their
     * table / view / function), e.g. {@code TABLE sales.orders} for the column {@code sales.orders.amount}. Equal to
     * {@link #objectType} / {@link #objectName} for top-level objects. Used to group changes per object (N-database
     * matrix).
     */
    private ObjectType rootType;

    private String rootName;

    /** Human readable description of what changed. */
    private String details;

    private String upSql;

    private String downSql;

    /**
     * Cross-database compare: the object exists only in the current database, not in the target snapshot (a
     * {@code DELETED} change that would drop it). Never selected by default.
     */
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private boolean extraInTargetDb;

    /** Warnings that apply to this change (data loss, manual action ...); also listed in the result's warnings. */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @Builder.Default
    private java.util.List<String> warnings = new java.util.ArrayList<>();

    /** Safety of {@link #upSql} on the target database (set by the migration safety analyzer, otherwise null). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private com.vodianoy.pgvc.safety.SafetyReport safety;

    /** Identity used to pair a change with its inverse from the reverse diff (not serialized). */
    @JsonIgnore
    private String pairKey;
}
