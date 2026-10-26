package com.vodianoy.pgvc.storage;

/**
 * Version-control state of one environment.
 *
 * @param reachable          the database accepted a connection
 * @param initialized        _pgvc tables and trigger functions exist
 * @param ddlTriggerEnabled  event trigger pgvc_ddl_trigger exists and is enabled
 * @param dropTriggerEnabled event trigger pgvc_drop_trigger exists and is enabled
 * @param superuser          the configured user is a SUPERUSER (needed to initialize)
 * @param head               current HEAD commit or null
 * @param commitCount        number of rows in _pgvc.commits
 * @param pendingDdlCount    DDL commands captured since the last commit
 * @param error              connection error when not reachable
 */
public record PgVcStatus(String environmentId, boolean reachable, boolean initialized, boolean ddlTriggerEnabled,
                         boolean dropTriggerEnabled, boolean superuser, String head, long commitCount,
                         long pendingDdlCount, String error) {
}
