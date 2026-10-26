package com.vodianoy.pgvc.core.repo;

/** Row of _pgvc.ddl_audit_log. */
public record AuditEntry(long id, String eventType, String commandTag, String objectType, String objectIdentity,
                         String rawQuery, String dbUser, String executedAt) {
}
