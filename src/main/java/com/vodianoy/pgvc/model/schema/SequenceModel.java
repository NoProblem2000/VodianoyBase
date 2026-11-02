package com.vodianoy.pgvc.model.schema;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Sequence from pg_sequence (relkind 'S'). Sequences of identity columns belong to the column and are not listed. */
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

    private long startValue;

    private long incrementBy;

    private long minValue;

    private long maxValue;

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
