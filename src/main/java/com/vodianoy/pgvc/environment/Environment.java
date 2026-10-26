package com.vodianoy.pgvc.environment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One database connection ("contour"): Dev, Test, Stage, Prod ...
 * Stored in pgvc-environments.json.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Environment {

    public static final String DEFAULT_SCHEMA = "public";

    /** Stable identifier derived from the name (e.g. "dev", "prod-eu"). */
    private String id;

    /** Display name. */
    private String name;

    /** jdbc:postgresql://host:port/database */
    private String jdbcUrl;

    private String username;

    /** Stored in plain text in the local JSON file; never returned by the REST API. */
    private String password;

    /**
     * Default schema of the connection (search_path) used for unqualified names.
     * Version control itself tracks all user schemas of the database.
     */
    @Builder.Default
    private String schemaName = DEFAULT_SCHEMA;

    /** Badge color on the commit graph, #rrggbb. */
    private String colorBadge;
}
