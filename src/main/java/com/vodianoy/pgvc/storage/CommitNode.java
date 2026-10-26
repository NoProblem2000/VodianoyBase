package com.vodianoy.pgvc.storage;

import java.time.OffsetDateTime;

/** One node of the commit graph (row of _pgvc.commits without the large SQL / snapshot columns). */
public record CommitNode(String hash, String parentHash, String author, String message, OffsetDateTime createdAt) {
}
