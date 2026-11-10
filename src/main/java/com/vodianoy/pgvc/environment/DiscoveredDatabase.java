package com.vodianoy.pgvc.environment;

/**
 * A database of the server (pg_database, templates excluded).
 *
 * @param comment   COMMENT ON DATABASE, null when there is none
 * @param canConnect the user has the CONNECT privilege on it
 */
public record DiscoveredDatabase(String name, String comment, String prettySize, boolean canConnect) {
}
