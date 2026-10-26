package com.vodianoy.pgvc.core.repo;

/** Lightweight commit row for the graph. createdAt is ISO-8601. */
public record CommitSummary(String hash, String parentHash, String author, String message, String createdAt) {
}
