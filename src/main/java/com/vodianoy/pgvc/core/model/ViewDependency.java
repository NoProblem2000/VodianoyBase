package com.vodianoy.pgvc.core.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Relation (and optionally column) that a view depends on, from pg_depend of the view's rewrite rule.
 *
 * @param column referenced column or null when the whole relation is referenced
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ViewDependency(String schema, String name, String column) {

    @JsonIgnore
    public String relationKey() {
        return schema + "." + name;
    }
}
