package com.vodianoy.pgvc.vcs;

import java.time.OffsetDateTime;
import java.util.List;

/** Commits of all environments merged into one graph, plus where every environment's HEAD is (the badges). */
public record GlobalGraph(List<Commit> commits, List<EnvironmentHead> environments) {

    /** A commit and the environments whose history contains it. */
    public record Commit(String hash, String parentHash, String author, String message, OffsetDateTime createdAt,
                         List<String> environments) {
    }

    public record EnvironmentHead(String id, String name, String colorBadge, boolean active, boolean reachable,
                                  boolean initialized, String head, String error) {
    }
}
