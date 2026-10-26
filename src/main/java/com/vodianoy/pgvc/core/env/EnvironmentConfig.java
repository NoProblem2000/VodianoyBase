package com.vodianoy.pgvc.core.env;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One database connection ("contour"): Dev, Test, Stage, Prod ...
 *
 * @param id       stable identifier (slug)
 * @param name     display name
 * @param color    badge color (#rrggbb)
 * @param jdbcUrl  jdbc:postgresql://host:port/database
 * @param username database user (developer / superuser)
 * @param password password (stored in plain text in pgvc-environments.json)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EnvironmentConfig(String id, String name, String color, String jdbcUrl, String username,
                                String password) {

    /** Copy without the password, safe to send to the browser. */
    public EnvironmentConfig masked() {
        return new EnvironmentConfig(id, name, color, jdbcUrl, username, password == null || password.isEmpty() ? "" : "********");
    }
}
