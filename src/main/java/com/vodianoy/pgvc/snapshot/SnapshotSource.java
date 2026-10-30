package com.vodianoy.pgvc.snapshot;

import java.time.Instant;

/** Metadata of an uploaded snapshot file (everything but the schema itself). */
public record SnapshotSource(String formatVersion, Instant exportedAt, String sourceEnvironmentName, String sourceBranch,
                             String sourceCommitHash, String sourceCommitMessage, int serverVersion) {

    static SnapshotSource of(SnapshotBundleDto bundle) {
        return new SnapshotSource(bundle.getFormatVersion(), bundle.getExportedAt(), bundle.getSourceEnvironmentName(),
                bundle.getSourceBranch(), bundle.getSourceCommitHash(), bundle.getSourceCommitMessage(),
                bundle.getSchemaSnapshot() == null ? 0 : bundle.getSchemaSnapshot().getServerVersion());
    }
}
