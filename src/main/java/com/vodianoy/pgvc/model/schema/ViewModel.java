package com.vodianoy.pgvc.model.schema;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** View (relkind 'v') or materialized view (relkind 'm'). */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ViewModel {

    private long oid;

    private String schemaName;

    private String name;

    private boolean materialized;

    /** pg_get_viewdef(oid, true): the SELECT of the view. */
    private String definition;

    /** Materialized view contains data (created WITH DATA / refreshed). Always true for plain views. */
    private boolean populated;

    /** Hash of the stored rewrite rule; does not change when referenced objects are renamed. */
    private String signature;

    /** Relations and columns this view reads (for safe DROP VIEW / ALTER / CREATE VIEW ordering). */
    @Builder.Default
    private List<ViewDependency> dependencies = new ArrayList<>();

    /** COMMENT ON VIEW / MATERIALIZED VIEW, null when there is none. */
    private String comment;

    /** Owner role; null when owners are not tracked (ignore rules or an older snapshot). */
    private String owner;

    /**
     * Privileges granted to other roles than the owner: {@code grantee=PRIVILEGE} or {@code grantee=PRIVILEGE*} (with
     * grant option), grantee quoted when needed, {@code PUBLIC} for everyone. Null when grants are not tracked.
     */
    private List<String> acl;

    /** Indexes of a materialized view by name. */
    @Builder.Default
    private Map<String, IndexModel> indexes = new LinkedHashMap<>();

    @JsonIgnore
    public String getQualifiedName() {
        return DatabaseSnapshot.key(schemaName, name);
    }
}
