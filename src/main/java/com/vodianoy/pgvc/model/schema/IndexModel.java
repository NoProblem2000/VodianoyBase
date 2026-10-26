package com.vodianoy.pgvc.model.schema;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Standalone index from pg_index (relkind 'i', or 'I' for an index on a partitioned table). */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class IndexModel {

    private long oid;

    private String name;

    /** pg_get_indexdef(indexrelid): a complete CREATE INDEX statement. */
    private String definition;

    private boolean unique;

    /** Access method: btree, hash, gin, gist, brin, ... */
    private String method;

    /** WHERE clause of a partial index, otherwise null. */
    private String predicate;

    /** Hash of pg_index (columns, opclasses, expressions, predicate); does not change on renames. */
    private String signature;
}
