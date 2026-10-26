package com.vodianoy.pgvc.vcs;

import com.fasterxml.jackson.databind.JsonNode;
import com.vodianoy.pgvc.model.schema.DatabaseSnapshot;

import java.time.OffsetDateTime;

/** Everything stored for one commit. */
public record CommitDetails(String hash, String parentHash, String author, String message, OffsetDateTime createdAt,
                            String upSql, String downSql, DatabaseSnapshot schemaSnapshot, JsonNode ddlLogHistory) {
}
