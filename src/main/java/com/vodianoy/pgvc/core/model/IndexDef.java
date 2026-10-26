package com.vodianoy.pgvc.core.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Standalone index (not backing a PK/UNIQUE/EXCLUDE constraint and not an auto-created partition child index).
 *
 * @param definition pg_get_indexdef(indexrelid)
 * @param signature  rename-invariant hash of pg_index (indkey, opclasses, expressions, predicate)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IndexDef(long oid, String schema, String name, long tableOid, String tableSchema, String tableName,
                       boolean unique, String definition, String signature) {

    @JsonIgnore
    public String key() {
        return schema + "." + name;
    }

    @JsonIgnore
    public String tableKey() {
        return tableSchema + "." + tableName;
    }

    /** Part of the definition after " USING " (access method, columns, predicate) - independent of names. */
    @JsonIgnore
    public String usingClause() {
        int idx = definition == null ? -1 : definition.indexOf(" USING ");
        return idx < 0 ? String.valueOf(definition) : definition.substring(idx + 7);
    }
}
