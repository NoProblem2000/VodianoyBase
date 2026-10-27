package com.vodianoy.pgvc.environment;

import com.vodianoy.pgvc.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Environment management: list, add, update, delete, test the connection, switch the active one. */
@RestController
@RequestMapping("/api/environments")
@RequiredArgsConstructor
public class EnvironmentController {

    private final EnvironmentManagerService environments;

    @GetMapping
    public List<EnvironmentView> list() {
        return environments.listViews();
    }

    @GetMapping("/{id}")
    public EnvironmentView get(@PathVariable String id) {
        return environments.getView(id);
    }

    @PostMapping
    public ResponseEntity<EnvironmentView> add(@RequestBody Environment environment) {
        Environment saved = environments.addEnvironment(environment);
        return ResponseEntity.status(HttpStatus.CREATED).body(environments.getView(saved.getId()));
    }

    @PutMapping("/{id}")
    public EnvironmentView update(@PathVariable String id, @RequestBody Environment environment) {
        environments.updateEnvironment(id, environment);
        return environments.getView(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        environments.deleteEnvironment(id);
        return ResponseEntity.noContent().build();
    }

    /** Tests unsaved connection settings with SELECT version(). */
    @PostMapping("/test")
    public ConnectionTestResult test(@RequestBody Environment candidate) {
        return environments.testConnection(candidate);
    }

    /** Tests a saved environment with SELECT version(). */
    @PostMapping("/{id}/test")
    public ConnectionTestResult testSaved(@PathVariable String id) {
        return environments.testConnection(id);
    }

    @GetMapping("/active")
    public ResponseEntity<EnvironmentView> active() {
        return environments.getActiveEnvironment()
                .map(e -> ResponseEntity.ok(environments.getView(e.getId())))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PutMapping("/active")
    public EnvironmentView activate(@RequestBody ActivateEnvironmentRequest request) {
        if (request == null || request.environmentId() == null || request.environmentId().isBlank()) {
            throw ApiException.badRequest("env.idRequired");
        }
        environments.activateEnvironment(request.environmentId());
        return environments.getView(request.environmentId());
    }
}
