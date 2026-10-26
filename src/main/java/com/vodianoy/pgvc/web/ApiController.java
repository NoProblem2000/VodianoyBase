package com.vodianoy.pgvc.web;

import com.vodianoy.pgvc.core.env.EnvironmentConfig;
import com.vodianoy.pgvc.core.env.EnvironmentStore;
import com.vodianoy.pgvc.core.service.Dtos;
import com.vodianoy.pgvc.core.service.PgvcService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** REST API consumed by the React UI. */
@RestController
@RequestMapping("/api")
public class ApiController {

    private final EnvironmentStore environments;
    private final PgvcService service;

    public ApiController(EnvironmentStore environments, PgvcService service) {
        this.environments = environments;
        this.service = service;
    }

    // ---- environments

    @GetMapping("/environments")
    public List<EnvironmentConfig> listEnvironments() {
        return environments.list().stream().map(EnvironmentConfig::masked).toList();
    }

    @PostMapping("/environments")
    public EnvironmentConfig createEnvironment(@RequestBody EnvironmentConfig env) {
        return environments.save(new EnvironmentConfig(null, env.name(), env.color(), env.jdbcUrl(), env.username(),
                env.password())).masked();
    }

    @PutMapping("/environments/{id}")
    public EnvironmentConfig updateEnvironment(@PathVariable String id, @RequestBody EnvironmentConfig env) {
        environments.get(id);
        return environments.save(new EnvironmentConfig(id, env.name(), env.color(), env.jdbcUrl(), env.username(),
                env.password())).masked();
    }

    @DeleteMapping("/environments/{id}")
    public ResponseEntity<Void> deleteEnvironment(@PathVariable String id) {
        environments.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/environments/test")
    public Dtos.ConnectionTest testConnection(@RequestBody EnvironmentConfig env) {
        return service.testConnection(env);
    }

    @GetMapping("/environments/{id}/status")
    public Dtos.EnvironmentStatus status(@PathVariable String id) {
        return service.status(id);
    }

    @PostMapping("/environments/{id}/install")
    public Dtos.EnvironmentStatus install(@PathVariable String id) {
        return service.install(id);
    }

    // ---- history

    @GetMapping("/graph")
    public Dtos.Graph graph() {
        return service.graph();
    }

    @GetMapping("/environments/{id}/commits/{hash}")
    public Dtos.CommitDetails commit(@PathVariable String id, @PathVariable String hash) {
        return service.commit(id, hash);
    }

    @GetMapping("/environments/{id}/compare")
    public Dtos.CompareResult compare(@PathVariable String id, @RequestParam(required = false) String from,
                                      @RequestParam String to) {
        return service.compare(id, from, to);
    }

    // ---- Mode A

    @GetMapping("/environments/{id}/working-tree")
    public Dtos.WorkingTree workingTree(@PathVariable String id) {
        return service.workingTree(id);
    }

    @PostMapping("/environments/{id}/commit")
    public Dtos.CommitResult commitWorkingTree(@PathVariable String id, @RequestBody Dtos.CommitRequest request) {
        return service.commitWorkingTree(id, request);
    }

    // ---- Mode B

    @PostMapping("/environments/{id}/execute")
    public Dtos.CommitResult executeAndCommit(@PathVariable String id, @RequestBody Dtos.ExecuteRequest request) {
        return service.executeAndCommit(id, request);
    }

    // ---- multi-environment

    @PostMapping("/environments/{id}/push")
    public Dtos.PushResult push(@PathVariable String id, @RequestBody Dtos.PushRequest request) {
        return service.push(id, request);
    }

    @GetMapping("/bundle")
    public ResponseEntity<byte[]> bundle(@RequestParam String source, @RequestParam(required = false) String from,
                                         @RequestParam String to) {
        Dtos.Bundle bundle = service.exportBundle(source, from, to);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(bundle.fileName()).build().toString())
                .contentType(new MediaType("application", "sql", StandardCharsets.UTF_8))
                .body(bundle.sql().getBytes(StandardCharsets.UTF_8));
    }
}
