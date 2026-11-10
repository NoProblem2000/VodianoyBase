package com.vodianoy.pgvc.vcs;

/**
 * Runs a quick fix the server found in the live schema of an environment.
 *
 * @param objectName the object of the fix, e.g. {@code mdc.mnemo_schemes.id}
 * @param kind       optional kind of the fix, e.g. {@code CROSS_SCHEMA_SEQUENCE}
 */
public record QuickFixRequest(String envId, String objectName, String kind, String lockTimeout, String statementTimeout) {
}
