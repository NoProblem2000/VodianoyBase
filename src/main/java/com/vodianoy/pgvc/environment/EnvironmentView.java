package com.vodianoy.pgvc.environment;

/** Environment as returned by the REST API: the password is never sent, only whether one is set. */
public record EnvironmentView(String id, String name, String jdbcUrl, String username, boolean hasPassword,
                              String schemaName, String colorBadge, boolean active) {

    static EnvironmentView of(Environment e, boolean active) {
        return new EnvironmentView(e.getId(), e.getName(), e.getJdbcUrl(), e.getUsername(),
                e.getPassword() != null && !e.getPassword().isEmpty(), e.getSchemaName(), e.getColorBadge(), active);
    }
}
