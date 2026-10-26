package com.vodianoy.pgvc.vcs;

import com.vodianoy.pgvc.diff.SchemaDiffResult;
import com.vodianoy.pgvc.storage.AuditLogEntry;

import java.util.List;

/**
 * Uncommitted changes of an environment: HEAD snapshot vs live schema, plus the DDL captured by the event triggers.
 *
 * @param head current HEAD (null for an empty history; then everything in the database is "added")
 */
public record WorkingTreeStatus(String environmentId, String head, SchemaDiffResult diff, List<AuditLogEntry> auditLog) {
}
