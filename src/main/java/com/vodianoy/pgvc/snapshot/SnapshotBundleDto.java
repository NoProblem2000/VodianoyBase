package com.vodianoy.pgvc.snapshot;

import com.vodianoy.pgvc.model.schema.DatabaseSnapshot;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Reference schema snapshot file ({@code .pgsnap.json}): the full schema of one commit (or of a live database) that
 * is carried to an isolated site and compared with the database there.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SnapshotBundleDto {

    public static final String FORMAT_VERSION = "1.0";
    public static final String FILE_EXTENSION = ".pgsnap.json";

    @Builder.Default
    private String formatVersion = FORMAT_VERSION;

    private Instant exportedAt;

    private String sourceEnvironmentName;

    /** The history the commit was taken from: the source environment (PgGitVisualizer has no named branches). */
    private String sourceBranch;

    /** Commit the snapshot belongs to; null when it was taken from a live database without commits. */
    private String sourceCommitHash;

    private String sourceCommitMessage;

    private DatabaseSnapshot schemaSnapshot;
}
