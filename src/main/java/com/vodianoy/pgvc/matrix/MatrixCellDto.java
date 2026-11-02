package com.vodianoy.pgvc.matrix;

import java.util.List;

/**
 * One object in one database.
 *
 * @param differences what differs from the baseline, one line per change (empty when identical)
 * @param upSql       SQL that brings this object in this database to the state of the baseline
 * @param downSql     SQL that reverts {@code upSql}
 * @param warnings    data loss / manual action warnings of {@code upSql}
 */
public record MatrixCellDto(MatrixCellStatus status, List<String> differences, String upSql, String downSql,
                            List<String> warnings) {

    public static MatrixCellDto of(MatrixCellStatus status) {
        return new MatrixCellDto(status, List.of(), "", "", List.of());
    }
}
