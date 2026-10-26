package com.vodianoy.pgvc.storage;

import java.time.OffsetDateTime;

/** Row of _pgvc.ddl_audit_log: one DDL command captured by the event triggers. */
public record AuditLogEntry(long id, String eventType, String commandTag, String objectType, String objectIdentity,
                            String rawQuery, String dbUser, OffsetDateTime executedAt) {
}
