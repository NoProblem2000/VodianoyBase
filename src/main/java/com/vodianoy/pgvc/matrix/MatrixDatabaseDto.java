package com.vodianoy.pgvc.matrix;

/**
 * One column of the matrix: a live environment or an uploaded snapshot.
 *
 * @param id            {@code env:<environment id>} or {@code snapshot:<index of the upload>}
 * @param kind          {@code ENVIRONMENT} or {@code SNAPSHOT}
 * @param description   snapshot: source environment and commit; environment: null
 * @param error         why the database could not be read, otherwise null
 * @param serverVersion server_version_num, 0 when unknown
 * @param identical     objects identical to the baseline
 * @param modified      objects that differ
 * @param missing       objects of the baseline that are missing here
 * @param extra         objects that exist only here
 */
public record MatrixDatabaseDto(String id, String name, String kind, boolean baseline, String description, String error,
                                int serverVersion, int identical, int modified, int missing, int extra) {
}
