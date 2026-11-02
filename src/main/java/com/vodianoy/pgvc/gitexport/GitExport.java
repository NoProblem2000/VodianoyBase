package com.vodianoy.pgvc.gitexport;

import java.util.SortedMap;

/**
 * Rendered export: relative path -> file content (sorted, so the output is deterministic).
 *
 * @param commitHash exported commit, null for the live schema
 * @param zipName    file name of the .zip download
 */
public record GitExport(String envId, String commitHash, String zipName, SortedMap<String, String> files) {
}
