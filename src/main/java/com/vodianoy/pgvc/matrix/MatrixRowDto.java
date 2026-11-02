package com.vodianoy.pgvc.matrix;

import com.vodianoy.pgvc.diff.ObjectType;

import java.util.Map;

/**
 * One top-level object (schema, extension, type, table, partition, view, sequence, function, procedure) across all
 * compared databases. Columns, constraints, indexes, triggers and comments are part of their table's row.
 *
 * @param differs at least one database is not identical to the baseline
 * @param cells   by database id ({@link MatrixDatabaseDto#id()})
 */
public record MatrixRowDto(ObjectType type, String name, boolean differs, Map<String, MatrixCellDto> cells) {
}
