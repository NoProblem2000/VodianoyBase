package com.vodianoy.pgvc.storage;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/** Complete row of _pgvc.commits. JSON columns are kept as text exactly as stored. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommitRecord {

    private String hash;
    private String parentHash;
    private String author;
    private String message;
    private OffsetDateTime createdAt;
    private String upSql;
    private String downSql;
    /** schema_snapshot (DatabaseSnapshot JSON). */
    private String schemaSnapshotJson;
    /** ddl_log_history (JSON array of audit entries) or null. */
    private String ddlLogHistoryJson;

    public CommitNode toNode() {
        return new CommitNode(hash, parentHash, author, message, createdAt);
    }
}
