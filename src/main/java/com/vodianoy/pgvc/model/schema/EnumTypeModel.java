package com.vodianoy.pgvc.model.schema;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** ENUM type from pg_type (typtype 'e') with its labels from pg_enum in enumsortorder. */
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class EnumTypeModel {

    private long oid;

    private String schemaName;

    private String name;

    /** Labels in sort order. */
    @Builder.Default
    private List<String> values = new ArrayList<>();

    /** Owner role; null when owners are not tracked. */
    private String owner;

    /** COMMENT ON TYPE, null when there is none. */
    private String comment;

    @JsonIgnore
    public String getQualifiedName() {
        return DatabaseSnapshot.key(schemaName, name);
    }
}
