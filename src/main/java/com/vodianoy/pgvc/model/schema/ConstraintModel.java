package com.vodianoy.pgvc.model.schema;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** PRIMARY KEY / FOREIGN KEY / UNIQUE / CHECK / EXCLUDE constraint from pg_constraint. */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ConstraintModel {

    private long oid;

    private String name;

    private ConstraintType type;

    /** pg_get_constraintdef(oid), e.g. {@code FOREIGN KEY (customer_id) REFERENCES sales.customers(id)}. */
    private String definition;

    /** Constrained columns (conkey) in key order. */
    @Builder.Default
    private List<String> columns = new ArrayList<>();

    /** Referenced table of a foreign key as {@code schema.table}, otherwise null. */
    private String referencedTable;

    /** Referenced columns of a foreign key (confkey). */
    @Builder.Default
    private List<String> referencedColumns = new ArrayList<>();

    private boolean deferrable;

    private boolean initiallyDeferred;

    /** False for constraints added with NOT VALID and not validated yet. */
    private boolean validated;

    /**
     * Hash of the catalog representation (attnums, referenced oid, actions, CHECK expression tree). Does not change
     * when columns or tables are renamed, so it tells a real change from a rename.
     */
    private String signature;
}
