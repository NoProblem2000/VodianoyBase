package com.vodianoy.pgvc.diff;

import com.fasterxml.jackson.annotation.JsonIgnore;
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

    private ChangeType changeType;

    private ObjectType objectType;

    /** Qualified name in the new state, e.g. {@code sales.orders}, {@code sales.orders.amount}. */
    private String objectName;

    /** Human readable description of what changed. */
    private String details;

    private String upSql;

    private String downSql;

    /** Identity used to pair a change with its inverse from the reverse diff (not serialized). */
    @JsonIgnore
    private String pairKey;
}
