package com.vodianoy.pgvc.gitexport;

import java.util.List;

/**
 * Result of writing the export into a folder.
 *
 * @param commitHash exported commit, null when the live schema was exported
 * @param written    files created or changed (relative paths)
 * @param unchanged  files that already had the same content
 * @param deleted    generated files of objects that no longer exist (relative paths)
 * @param files      all files of the export
 */
public record GitExportResult(String envId, String commitHash, String targetPath, List<String> written, int unchanged,
                              List<String> deleted, int files) {
}
