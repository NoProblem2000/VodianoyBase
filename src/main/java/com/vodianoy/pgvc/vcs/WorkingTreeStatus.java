package com.vodianoy.pgvc.vcs;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.vodianoy.pgvc.diff.SchemaDiffResult;
import com.vodianoy.pgvc.model.schema.DatabaseSnapshot;
import com.vodianoy.pgvc.storage.AuditLogEntry;

import java.util.List;

/**
 * Uncommitted changes of an environment: HEAD snapshot vs live schema, plus the DDL captured by the event triggers.
 *
 * @param head            current HEAD (null for an empty history; then everything in the database is "added")
 * @param introspectionMs time the catalog of the live database took to read
 * @param durationMs      time of the whole working-tree computation on the server (introspection, diff, safety)
 * @param snapshot        the live snapshot the diff was computed from, only when asked for
 */
public record WorkingTreeStatus(String environmentId, String head, SchemaDiffResult diff, List<AuditLogEntry> auditLog,
                                long introspectionMs, long durationMs,
                                @JsonInclude(JsonInclude.Include.NON_NULL) DatabaseSnapshot snapshot) {
}
