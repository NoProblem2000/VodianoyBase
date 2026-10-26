package com.vodianoy.pgvc.core.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Sequence (pg_sequence + pg_class.relkind = 'S'). Identity sequences are not included. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SequenceDef(long oid, String schema, String name, String dataType, long start, long increment,
                          long min, long max, long cache, boolean cycle, OwnedBy ownedBy) {

    @JsonIgnore
    public String key() {
        return schema + "." + name;
    }

    @JsonIgnore
    public boolean sameOptions(SequenceDef o) {
        return dataType.equals(o.dataType) && start == o.start && increment == o.increment
                && min == o.min && max == o.max && cache == o.cache && cycle == o.cycle;
    }
}
