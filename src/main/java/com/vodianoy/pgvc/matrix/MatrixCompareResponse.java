package com.vodianoy.pgvc.matrix;

import com.vodianoy.pgvc.diff.DiffIgnoreRules;

import java.util.List;

/**
 * Result of an N-database comparison.
 *
 * @param baselineId  id of the baseline column
 * @param databases   columns in request order (environments first, then snapshots)
 * @param rows        one per top-level object, sorted by type and name
 * @param ignoreRules rules that were applied
 */
public record MatrixCompareResponse(String baselineId, List<MatrixDatabaseDto> databases, List<MatrixRowDto> rows,
                                    DiffIgnoreRules ignoreRules) {
}
