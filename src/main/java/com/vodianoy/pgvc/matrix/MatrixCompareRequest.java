package com.vodianoy.pgvc.matrix;

import com.vodianoy.pgvc.diff.DiffIgnoreRules;

import java.util.List;

/**
 * Compare N databases at once.
 *
 * @param envIds      live environments to read
 * @param snapshots   uploaded reference snapshots
 * @param baseline    database every other one is compared with: {@code env:<id>} or {@code snapshot:<index>};
 *                    blank: the first environment (or the first snapshot)
 * @param ignoreRules one-off rules for this comparison; null: the saved default rules
 */
public record MatrixCompareRequest(List<String> envIds, List<MatrixSnapshotUpload> snapshots, String baseline,
                                   DiffIgnoreRules ignoreRules) {
}
