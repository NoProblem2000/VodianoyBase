package com.vodianoy.pgvc.model.schema;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.vodianoy.pgvc.common.SaturatingLongDeserializer;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Sequence from pg_sequence (relkind 'S'). Sequences of identity columns belong to the column and are not listed. */
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class SequenceModel {

    private long oid;

    private String schemaName;

    private String name;

    /** smallint, integer or bigint. */
    private String dataType;

    @JsonDeserialize(using = SaturatingLongDeserializer.class)
    private long startValue;

    @JsonDeserialize(using = SaturatingLongDeserializer.class)
    private long incrementBy;

    @JsonDeserialize(using = SaturatingLongDeserializer.class)
    private long minValue;

    @JsonDeserialize(using = SaturatingLongDeserializer.class)
    private long maxValue;

    @JsonDeserialize(using = SaturatingLongDeserializer.class)
    private long cacheSize;

    private boolean cycle;

    /** Owning table of a serial sequence as {@code schema.table} (ALTER SEQUENCE ... OWNED BY), otherwise null. */
    private String ownedByTable;

    /** Owning column of a serial sequence, otherwise null. */
    private String ownedByColumn;

    /** Owner role; null when owners are not tracked (ignore rules or an older snapshot). */
    private String owner;

    /**
     * Privileges granted to other roles than the owner: {@code grantee=PRIVILEGE} or {@code grantee=PRIVILEGE*} (with
     * grant option), grantee quoted when needed, {@code PUBLIC} for everyone. Null when grants are not tracked.
     */
    private List<String> acl;

    @JsonIgnore
    public String getQualifiedName() {
        return DatabaseSnapshot.key(schemaName, name);
    }
}
