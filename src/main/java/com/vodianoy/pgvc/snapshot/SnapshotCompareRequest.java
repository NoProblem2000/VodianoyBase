package com.vodianoy.pgvc.snapshot;

/** Compare the live schema of {@code envId} with an uploaded reference snapshot. */
public record SnapshotCompareRequest(String envId, SnapshotBundleDto bundle) {
}
