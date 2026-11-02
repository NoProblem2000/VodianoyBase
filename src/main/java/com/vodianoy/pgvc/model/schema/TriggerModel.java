package com.vodianoy.pgvc.model.schema;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * User trigger of a table from pg_trigger ({@code NOT tgisinternal}; triggers that PostgreSQL 13+ clones from a
 * partitioned table to its partitions are not listed separately).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class TriggerModel {

    private long oid;

    private String name;

    /** pg_get_triggerdef(oid, true): complete {@code CREATE [CONSTRAINT] TRIGGER ...} statement (without ';'). */
    private String definition;

    /** pg_trigger.tgenabled: {@code O} enabled, {@code D} disabled, {@code R} replica only, {@code A} always. */
    private String enabled;

    /** Trigger function as {@code schema.name(identity arguments)} (see {@link FunctionModel#getKey()}). */
    private String functionKey;
}
