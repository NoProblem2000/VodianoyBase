package com.vodianoy.pgvc.migration;

import com.vodianoy.pgvc.storage.CommitNode;

import java.util.List;

/**
 * Generated offline migration.
 *
 * @param fileName suggested file name, e.g. {@code pgvc-dev-a1b2c3d4-to-e5f6a7b8.sql}
 * @param commits  commits contained in the bundle, oldest first
 * @param sql      the self-contained script
 */
public record OfflineBundle(String sourceEnvironmentId, String fromCommitHash, String toCommitHash, String fileName,
                            List<CommitNode> commits, String sql) {
}
