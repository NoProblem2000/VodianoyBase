package com.vodianoy.pgvc.storage;

import java.util.List;

/**
 * Commit graph of one environment.
 *
 * @param head    commit _pgvc.refs.HEAD points to, or null for an empty history
 * @param commits all commits, newest first
 */
public record CommitGraph(String environmentId, String head, List<CommitNode> commits) {
}
