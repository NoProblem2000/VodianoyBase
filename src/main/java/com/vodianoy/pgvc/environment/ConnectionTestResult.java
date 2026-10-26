package com.vodianoy.pgvc.environment;

/**
 * Result of {@code SELECT version()} against an environment.
 *
 * @param success       connection works and the server is PostgreSQL 11+
 * @param version       full version() string
 * @param versionNumber server_version_num, e.g. 160004
 * @param superuser     whether the user can install event triggers
 * @param message       human readable outcome
 */
public record ConnectionTestResult(boolean success, String version, Integer versionNumber, boolean superuser,
                                   String message) {
}
