package com.vodianoy.pgvc.vcs;

/** A quick fix that ran: the SQL executed on the environment (not committed - it shows up in the working tree). */
public record QuickFixResult(String environmentId, String objectName, String sql) {
}
