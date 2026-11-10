package com.vodianoy.pgvc.diff;

/**
 * A ready-made fix for a problem found in the schema, shown under the change with "Copy" / "Use in editor".
 *
 * @param kind       machine-readable kind, e.g. {@code CROSS_SCHEMA_SEQUENCE}
 * @param objectName the object the fix is for, e.g. {@code mdc.mnemo_schemes.id}
 * @param message    the warning that explains the problem (language of the request)
 * @param sql        the statements that fix it
 */
public record QuickFix(String kind, String objectName, String message, String sql) {
}
