package com.vodianoy.pgvc.model.schema;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Installed extension from pg_extension (plpgsql, which every database has, is not listed). */
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ExtensionModel {

    private String name;

    /** Schema of the extension's objects. */
    private String schemaName;

    /** Installed version (pg_extension.extversion). */
    private String version;
}
