package com.vodianoy.pgvc.core.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Table constraint (PRIMARY KEY, FOREIGN KEY, UNIQUE, CHECK, EXCLUDE) read from pg_constraint.
 *
 * @param oid        pg_constraint.oid
 * @param name       constraint name
 * @param type       contype: p, f, u, c, x
 * @param definition pg_get_constraintdef(oid)
 * @param signature  rename-invariant hash of the catalog representation (attnums, conbin node tree)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConstraintDef(long oid, String name, String type, String definition, String signature) {

    @JsonIgnore
    public boolean foreignKey() {
        return "f".equals(type);
    }
}
