package com.vodianoy.pgvc.snapshot;

import com.vodianoy.pgvc.diff.DiffIgnoreRules;

/**
 * Compare the live schema of {@code envId} with an uploaded reference snapshot.
 *
 * @param ignoreRules one-off rules for this comparison; null: the saved default rules
 */
public record SnapshotCompareRequest(String envId, SnapshotBundleDto bundle, DiffIgnoreRules ignoreRules) {

    public SnapshotCompareRequest(String envId, SnapshotBundleDto bundle) {
        this(envId, bundle, null);
    }
}
