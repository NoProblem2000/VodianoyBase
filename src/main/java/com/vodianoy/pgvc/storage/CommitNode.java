package com.vodianoy.pgvc.storage;

import java.time.OffsetDateTime;

/**
 * One node of the commit graph (row of _pgvc.commits without the large SQL / snapshot columns).
 *
 * @param message     one-line summary shown in the graph
 * @param description longer description (details, ticket numbers); null when there is none
 */
public record CommitNode(String hash, String parentHash, String author, String message, String description,
                         OffsetDateTime createdAt) {
}
