package com.vodianoy.pgvc.snapshot;

/** An exported snapshot file: its name and the bundle. */
public record SnapshotExport(String fileName, SnapshotBundleDto bundle) {
}
