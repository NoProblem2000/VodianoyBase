package com.vodianoy.pgvc.core.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * View or materialized view (pg_class.relkind IN ('v', 'm')).
 *
 * @param definition   pg_get_viewdef(oid, true)
 * @param signature    hash of pg_rewrite.ev_action (rename-invariant)
 * @param dependencies relations/columns this view reads
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ViewDef(long oid, String schema, String name, boolean materialized, String definition,
                      String signature, List<ViewDependency> dependencies) {

    public ViewDef {
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
    }

    @JsonIgnore
    public String key() {
        return schema + "." + name;
    }
}
