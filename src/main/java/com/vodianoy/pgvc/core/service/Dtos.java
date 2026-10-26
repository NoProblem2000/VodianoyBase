package com.vodianoy.pgvc.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.vodianoy.pgvc.core.model.DatabaseSnapshot;
import com.vodianoy.pgvc.core.repo.AuditEntry;
import com.vodianoy.pgvc.core.repo.CommitSummary;

import java.util.List;

/** Request / response records of the version-control service (serialized as JSON by the web layer). */
public final class Dtos {

    private Dtos() {
    }

    public record EnvironmentStatus(String id, String name, String color, boolean reachable, boolean installed,
                                    boolean triggersInstalled, boolean superuser, String serverVersion,
                                    String head, String error) {
    }

    public record GraphCommit(String hash, String parentHash, String author, String message, String createdAt,
                              List<String> environments) {
    }

    public record Graph(List<GraphCommit> commits, List<EnvironmentStatus> environments) {
    }

    public record WorkingTree(String environmentId, String head, boolean hasChanges, String upSql, String downSql,
                              List<String> summary, List<String> warnings, List<AuditEntry> auditLog) {
    }

    public record CommitDetails(String environmentId, String hash, String parentHash, String author, String message,
                                String createdAt, String upSql, String downSql, JsonNode ddlLogHistory,
                                DatabaseSnapshot snapshot) {
    }

    public record CommitRequest(String message, String author, String upSql, String downSql, String expectedHead) {
    }

    public record ExecuteRequest(String message, String author, String upSql, String downSql, boolean allowDirty) {
    }

    public record PushRequest(String sourceEnvironmentId, String commitHash, boolean allowDirty) {
    }

    public record PushResult(String targetEnvironmentId, String previousHead, String newHead,
                             List<CommitSummary> appliedCommits) {
    }

    public record CommitResult(CommitSummary commit, List<String> summary) {
    }

    public record ConnectionTest(boolean ok, String serverVersion, boolean superuser, String message) {
    }

    public record CompareResult(String fromHash, String toHash, String upSql, String downSql, List<String> summary,
                                List<String> warnings) {
    }

    public record Bundle(String fileName, String sql) {
    }
}
