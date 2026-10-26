package com.vodianoy.pgvc.vcs;

/**
 * Commit of the working tree (Mode A).
 *
 * @param upSql        UP SQL as shown (and possibly edited) in the UI; blank = use the generated one
 * @param downSql      DOWN SQL as shown (and possibly edited) in the UI; blank = use the generated one
 * @param expectedHead HEAD the UI was looking at; when set and HEAD has moved since, the commit is rejected
 */
public record CommitRequest(String message, String author, String upSql, String downSql, String expectedHead) {
}
