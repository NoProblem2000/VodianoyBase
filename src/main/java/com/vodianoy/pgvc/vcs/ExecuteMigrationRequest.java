package com.vodianoy.pgvc.vcs;

/**
 * Mode B migration.
 *
 * @param upSql            the migration; {@code -- pgvc:step} lines split it into steps (one transaction each;
 *                         CONCURRENTLY statements run outside of a transaction)
 * @param downSql          blank: generated from the schema difference
 * @param allowDirty       run even if the database has uncommitted schema changes (they become part of the commit)
 * @param lockTimeout      {@code lock_timeout} of every step, blank = {@code pgvc.lock-timeout} (3s)
 * @param statementTimeout {@code statement_timeout} of every step, blank = {@code pgvc.statement-timeout} (60s)
 * @param preScript        runs before {@code upSql} (same transaction unless separated by a step marker)
 * @param postScript       runs after {@code upSql}
 * @param dryRun           run everything in a transaction that is rolled back at the end and report what happened
 */
public record ExecuteMigrationRequest(String message, String author, String upSql, String downSql, boolean allowDirty,
                                      String lockTimeout, String statementTimeout, String preScript, String postScript,
                                      boolean dryRun) {

    public ExecuteMigrationRequest(String message, String author, String upSql, String downSql, boolean allowDirty) {
        this(message, author, upSql, downSql, allowDirty, null, null, null, null, false);
    }
}
