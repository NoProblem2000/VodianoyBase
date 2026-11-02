package com.vodianoy.pgvc.model.schema;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Function or procedure from pg_proc (prokind 'f', 'p' or 'w'; aggregates are not version controlled).
 * Overloads are separate objects: the key includes the identity arguments, e.g. {@code sales.total(integer, text)}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class FunctionModel {

    private long oid;

    private String schemaName;

    private String name;

    /** pg_get_function_identity_arguments(oid), e.g. {@code order_id integer, OUT total numeric}. */
    private String identityArguments;

    /** pg_proc.prokind: {@code f} function, {@code p} procedure, {@code w} window function. */
    private String kind;

    /** pg_get_function_result(oid), e.g. {@code SETOF sales.orders}; null for procedures. */
    private String resultType;

    /** Language name, e.g. plpgsql, sql. */
    private String language;

    /** pg_get_functiondef(oid): complete {@code CREATE OR REPLACE FUNCTION ...} statement (without ';'). */
    private String definition;

    /** Owner role; null when owners are not tracked (ignore rules or an older snapshot). */
    private String owner;

    /**
     * Privileges granted to other roles than the owner, one per entry: {@code grantee=PRIVILEGE} or
     * {@code grantee=PRIVILEGE*} (with grant option); the grantee is quoted when needed, {@code PUBLIC} for everyone.
     * Null when grants are not tracked.
     */
    private List<String> acl;

    /** COMMENT ON FUNCTION, null when there is none. */
    private String comment;

    /** Map key: {@code schema.name(identity arguments)}. */
    @JsonIgnore
    public String getKey() {
        return key(schemaName, name, identityArguments);
    }

    public static String key(String schema, String name, String identityArguments) {
        return DatabaseSnapshot.key(schema, name) + "(" + (identityArguments == null ? "" : identityArguments) + ")";
    }

    @JsonIgnore
    public boolean isProcedure() {
        return "p".equals(kind);
    }
}
