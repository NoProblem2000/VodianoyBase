package com.vodianoy.pgvc.environment;

/**
 * Server connection used to browse databases and schemas before an environment is saved (DataGrip-like picker).
 *
 * @param id           id of the edited environment; when the password is null its stored password is used
 * @param host         server host; when blank, {@code jdbcUrl} is used
 * @param port         server port (5432 when null)
 * @param databaseName database to list the schemas of (discover-schemas only)
 * @param jdbcUrl      optional JDBC URL (connection parameters such as sslmode are kept)
 */
public record DiscoveryRequest(String id, String host, Integer port, String username, String password,
                               String databaseName, String jdbcUrl) {
}
