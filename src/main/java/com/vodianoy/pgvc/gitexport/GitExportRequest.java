package com.vodianoy.pgvc.gitexport;

/**
 * @param envId             environment to export
 * @param commitHash        commit whose schema is exported; blank: HEAD (the live schema when there are no commits)
 * @param targetPath        absolute path of a local folder (usually a Git working copy); blank: a .zip is returned
 * @param includeMigrations also write migrations/NNNN_hash_slug.up.sql / .down.sql (default true)
 */
public record GitExportRequest(String envId, String commitHash, String targetPath, Boolean includeMigrations) {
}
