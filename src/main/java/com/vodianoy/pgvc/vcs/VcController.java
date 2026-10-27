package com.vodianoy.pgvc.vcs;

import com.vodianoy.pgvc.diff.SchemaDiffResult;
import com.vodianoy.pgvc.introspection.PostgresSchemaIntrospector;
import com.vodianoy.pgvc.model.schema.DatabaseSnapshot;
import com.vodianoy.pgvc.storage.AuditLogEntry;
import com.vodianoy.pgvc.storage.CommitGraph;
import com.vodianoy.pgvc.storage.PgVcStatus;
import com.vodianoy.pgvc.storage.PgVcStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Version control REST API:
 * <ul>
 *   <li>{@code /api/environments/{envId}/pgvc/*} - _pgvc initialization, status, commit graph, HEAD, DDL audit log;</li>
 *   <li>{@code /api/environments/{envId}/schema/*} - live schema read from pg_catalog;</li>
 *   <li>{@code /api/environments/{envId}/vc/*} - working tree, commits (Mode A / Mode B), rollback;</li>
 *   <li>{@code /api/vc/*} - all environments: merged graph, comparison, push, link (baseline).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class VcController {

    private final PgVcStorageService storage;
    private final PostgresSchemaIntrospector introspector;
    private final VersionControlService versionControl;

    // ------------------------------------------------------------------ _pgvc metadata

    @PostMapping("/environments/{envId}/pgvc/initialize")
    public PgVcStatus initialize(@PathVariable String envId) {
        return storage.initializeEnvironment(envId);
    }

    @GetMapping("/environments/{envId}/pgvc/status")
    public PgVcStatus status(@PathVariable String envId) {
        return storage.getStatus(envId);
    }

    @GetMapping("/environments/{envId}/pgvc/commits")
    public CommitGraph commits(@PathVariable String envId) {
        return storage.getCommitGraph(envId);
    }

    @GetMapping("/environments/{envId}/pgvc/head")
    public Map<String, String> head(@PathVariable String envId) {
        // singletonMap allows a null value (empty history)
        return Collections.singletonMap("head", storage.getHead(envId).orElse(null));
    }

    @GetMapping("/environments/{envId}/pgvc/audit-log")
    public List<AuditLogEntry> auditLog(@PathVariable String envId) {
        return storage.readAuditLog(envId);
    }

    /** Clears the audit log completely, or up to and including {@code upToId}. */
    @DeleteMapping("/environments/{envId}/pgvc/audit-log")
    public Map<String, Integer> clearAuditLog(@PathVariable String envId, @RequestParam(required = false) Long upToId) {
        int deleted = upToId == null ? storage.clearAuditLog(envId) : storage.clearAuditLog(envId, upToId);
        return Map.of("deleted", deleted);
    }

    // ------------------------------------------------------------------ live schema

    @GetMapping("/environments/{envId}/schema/schemas")
    public List<String> schemas(@PathVariable String envId) {
        return introspector.listSchemas(envId);
    }

    /** Snapshot of the live schema; {@code ?schemas=sales,billing} restricts it to some schemas. */
    @GetMapping("/environments/{envId}/schema/snapshot")
    public DatabaseSnapshot snapshot(@PathVariable String envId,
                                     @RequestParam(name = "schemas", required = false) List<String> schemas) {
        return introspector.introspect(envId, schemas);
    }

    // ------------------------------------------------------------------ working tree, commits, rollback

    @GetMapping("/environments/{envId}/vc/working-tree")
    public WorkingTreeStatus workingTree(@PathVariable String envId) {
        return versionControl.getWorkingTreeStatus(envId);
    }

    /** Mode A: record the working tree as a commit. */
    @PostMapping("/environments/{envId}/vc/commit")
    public CommitResult commit(@PathVariable String envId, @RequestBody CommitRequest request) {
        return versionControl.commitWorkingTree(envId, request);
    }

    /** Mode B: execute a migration and commit it in one transaction. */
    @PostMapping("/environments/{envId}/vc/execute")
    public CommitResult execute(@PathVariable String envId, @RequestBody ExecuteMigrationRequest request) {
        return versionControl.executeAndCommitSql(envId, request);
    }

    @GetMapping("/environments/{envId}/vc/commits/{hash}")
    public CommitDetails commitDetails(@PathVariable String envId, @PathVariable String hash) {
        return versionControl.getCommit(envId, hash);
    }

    /** Schema changes introduced by a commit (parent snapshot vs commit snapshot). */
    @GetMapping("/environments/{envId}/vc/commits/{hash}/changes")
    public SchemaDiffResult commitChanges(@PathVariable String envId, @PathVariable String hash) {
        return versionControl.getCommitChanges(envId, hash);
    }

    @GetMapping("/environments/{envId}/vc/rollback-plan")
    public RollbackPlan rollbackPlan(@PathVariable String envId, @RequestParam("target") String targetHash) {
        return versionControl.getRollbackPlan(envId, targetHash);
    }

    @PostMapping("/environments/{envId}/vc/rollback")
    public RollbackResult rollback(@PathVariable String envId, @RequestBody RollbackRequest request) {
        return versionControl.rollbackToCommit(envId, request);
    }

    // ------------------------------------------------------------------ all environments

    /** Commits of all environments in one graph with the HEAD badge of every environment. */
    @GetMapping("/vc/graph")
    public GlobalGraph graph() {
        return versionControl.getGlobalGraph();
    }

    @GetMapping("/vc/compare")
    public EnvironmentComparison compare(@RequestParam("source") String sourceEnvId,
                                         @RequestParam("target") String targetEnvId) {
        return versionControl.compareEnvironments(sourceEnvId, targetEnvId);
    }

    /** Online push of the missing commits from the source to the target environment. */
    @PostMapping("/vc/push")
    public PushResult push(@RequestBody PushRequest request) {
        return versionControl.pushCommitsToEnvironment(request);
    }

    /**
     * Baseline: links an environment whose schema already exists to the history of the source environment without
     * executing migrations (its live schema must match the chosen commit).
     */
    @PostMapping("/vc/link")
    public LinkResult link(@RequestBody LinkRequest request) {
        return versionControl.linkEnvironmentToHistory(request);
    }
}
