package com.vodianoy.pgvc.storage;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Version-control metadata of one environment: initialization, status, commit graph, HEAD, DDL audit log. */
@RestController
@RequestMapping("/api/environments/{envId}/pgvc")
@RequiredArgsConstructor
public class PgVcController {

    private final PgVcStorageService storage;

    @PostMapping("/initialize")
    public PgVcStatus initialize(@PathVariable String envId) {
        return storage.initializeEnvironment(envId);
    }

    @GetMapping("/status")
    public PgVcStatus status(@PathVariable String envId) {
        return storage.getStatus(envId);
    }

    @GetMapping("/commits")
    public CommitGraph commits(@PathVariable String envId) {
        return storage.getCommitGraph(envId);
    }

    @GetMapping("/head")
    public Map<String, String> head(@PathVariable String envId) {
        // singletonMap allows a null value (empty history)
        return Collections.singletonMap("head", storage.getHead(envId).orElse(null));
    }

    @GetMapping("/audit-log")
    public List<AuditLogEntry> auditLog(@PathVariable String envId) {
        return storage.readAuditLog(envId);
    }

    /** Clears the audit log completely, or up to and including {@code upToId}. */
    @DeleteMapping("/audit-log")
    public Map<String, Integer> clearAuditLog(@PathVariable String envId, @RequestParam(required = false) Long upToId) {
        int deleted = upToId == null ? storage.clearAuditLog(envId) : storage.clearAuditLog(envId, upToId);
        return Map.of("deleted", deleted);
    }
}
