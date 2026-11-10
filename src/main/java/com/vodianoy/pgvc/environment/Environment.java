package com.vodianoy.pgvc.environment;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * One database connection ("contour"): Dev, Test, Stage, Prod ...
 * Stored in pgvc-environments.json.
 *
 * <p>Since v1.9.1 the connection is described by server ({@link #host}, {@link #port}), {@link #databaseName} and the
 * {@link #trackedSchemas} picked in the DataGrip-like database / schema browser; {@link #jdbcUrl} is derived from them.
 * Environments written by older versions have only {@code jdbcUrl}: host, port and database are parsed from it when the
 * file is loaded, and they keep tracking all user schemas.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Environment {

    public static final String DEFAULT_SCHEMA = "public";
    public static final int DEFAULT_PORT = 5432;

    /** Value of {@link #trackedSchemas} meaning "every user schema of the database, also ones created later". */
    public static final String ALL_USER_SCHEMAS = "ALL_USER_SCHEMAS";

    /** Stable identifier derived from the name (e.g. "dev", "prod-eu"). */
    private String id;

    /** Display name. */
    private String name;

    /** Server host name or address (without the port). */
    private String host;

    /** Server port, 5432 by default. */
    private Integer port;

    /** Database on the server. */
    private String databaseName;

    /** jdbc:postgresql://host:port/database[?parameters] - built from host / port / database when they are given. */
    private String jdbcUrl;

    private String username;

    /** Stored in plain text in the local JSON file; never returned by the REST API. */
    private String password;

    /**
     * Default schema of the connection (search_path) used for unqualified names.
     */
    @Builder.Default
    private String schemaName = DEFAULT_SCHEMA;

    /**
     * Schemas under version control: the names picked in the schema browser, or {@code [ALL_USER_SCHEMAS]} (also
     * null / empty in older files) for every user schema of the database.
     */
    @Builder.Default
    private List<String> trackedSchemas = new ArrayList<>(List.of(ALL_USER_SCHEMAS));

    /** Badge color on the commit graph, #rrggbb. */
    private String colorBadge;

    /** The schemas picked explicitly, or null when the environment tracks every user schema. */
    @JsonIgnore
    public List<String> explicitSchemas() {
        if (trackedSchemas == null || trackedSchemas.isEmpty() || trackedSchemas.contains(ALL_USER_SCHEMAS)) {
            return null;
        }
        return List.copyOf(trackedSchemas);
    }
}
