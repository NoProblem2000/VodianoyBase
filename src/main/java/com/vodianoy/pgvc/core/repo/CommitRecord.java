package com.vodianoy.pgvc.core.repo;

import com.vodianoy.pgvc.core.model.DatabaseSnapshot;

import java.time.Instant;

/**
 * Full row of _pgvc.commits.
 *
 * @param snapshotJson    schema_snapshot exactly as stored (used for hashing and bundle export)
 * @param ddlLogHistoryJson ddl_log_history JSON array or null
 */
public record CommitRecord(String hash, String parentHash, String author, String message, Instant createdAt,
                           String upSql, String downSql, String snapshotJson, String ddlLogHistoryJson) {

    public DatabaseSnapshot snapshot() {
        return com.vodianoy.pgvc.core.util.Json.read(snapshotJson, DatabaseSnapshot.class);
    }

    public CommitSummary summary() {
        return new CommitSummary(hash, parentHash, author, message, createdAt.toString());
    }
}
