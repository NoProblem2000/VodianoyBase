package com.vodianoy.pgvc.matrix;

import com.vodianoy.pgvc.snapshot.SnapshotBundleDto;

/**
 * An uploaded {@code .pgsnap.json} file.
 *
 * @param name column title (usually the file name); blank: taken from the snapshot
 */
public record MatrixSnapshotUpload(String name, SnapshotBundleDto bundle) {
}
