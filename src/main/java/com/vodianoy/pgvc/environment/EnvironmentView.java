package com.vodianoy.pgvc.environment;

import java.util.List;

/**
 * Environment as returned by the REST API: the password is never sent, only whether one is set.
 *
 * @param trackedSchemas the schemas under version control, {@code [ALL_USER_SCHEMAS]} for every user schema
 */
public record EnvironmentView(String id, String name, String host, Integer port, String databaseName, String jdbcUrl,
                              String username, boolean hasPassword, String schemaName, List<String> trackedSchemas,
                              String colorBadge, boolean active) {

    static EnvironmentView of(Environment e, boolean active) {
        return new EnvironmentView(e.getId(), e.getName(), e.getHost(), e.getPort(), e.getDatabaseName(), e.getJdbcUrl(),
                e.getUsername(), e.getPassword() != null && !e.getPassword().isEmpty(), e.getSchemaName(),
                e.getTrackedSchemas() == null || e.getTrackedSchemas().isEmpty()
                        ? List.of(Environment.ALL_USER_SCHEMAS) : List.copyOf(e.getTrackedSchemas()),
                e.getColorBadge(), active);
    }
}
