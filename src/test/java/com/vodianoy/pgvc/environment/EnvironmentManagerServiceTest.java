package com.vodianoy.pgvc.environment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vodianoy.pgvc.common.ApiException;
import com.vodianoy.pgvc.config.PgVcProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvironmentManagerServiceTest {

    @TempDir
    Path dir;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private EnvironmentManagerService service;

    @BeforeEach
    void setUp() {
        service = newService();
    }

    @AfterEach
    void tearDown() {
        service.destroy();
    }

    private EnvironmentManagerService newService() {
        PgVcProperties props = new PgVcProperties();
        props.setEnvironmentsFile(dir.resolve("pgvc-environments.json").toString());
        props.setConnectTimeoutSeconds(1);
        return new EnvironmentManagerService(props, mapper);
    }

    private static Environment env(String name) {
        return Environment.builder().name(name).jdbcUrl("jdbc:postgresql://127.0.0.1:1/" + name)
                .username("postgres").password("secret").build();
    }

    @Test
    void addAppliesDefaultsAndPersists() throws Exception {
        Environment dev = service.addEnvironment(Environment.builder().name("Dev Eu").jdbcUrl("jdbc:postgresql://h/db").build());

        assertThat(dev.getId()).isEqualTo("dev-eu");
        assertThat(dev.getSchemaName()).isEqualTo("public");
        assertThat(dev.getColorBadge()).matches("#[0-9a-f]{6}");
        assertThat(service.getActiveEnvironment()).map(Environment::getId).contains("dev-eu");

        JsonNode file = mapper.readTree(Files.readString(dir.resolve("pgvc-environments.json")));
        assertThat(file.get("activeEnvironmentId").asText()).isEqualTo("dev-eu");
        assertThat(file.get("environments").get(0).get("schemaName").asText()).isEqualTo("public");
    }

    @Test
    void idsAreUniqueAndAvoidReservedPaths() {
        assertThat(service.addEnvironment(env("Prod")).getId()).isEqualTo("prod");
        assertThat(service.addEnvironment(env("prod")).getId()).isEqualTo("prod-2");
        assertThat(service.addEnvironment(env("Test")).getId()).isEqualTo("env-test");
        assertThat(service.addEnvironment(env("Active")).getId()).isEqualTo("env-active");
    }

    @Test
    void activeEnvironmentSurvivesRestart() {
        service.addEnvironment(env("Dev"));
        service.addEnvironment(env("Prod"));
        service.activateEnvironment("prod");

        EnvironmentManagerService reloaded = newService();

        assertThat(reloaded.getActiveEnvironment()).map(Environment::getId).contains("prod");
        assertThat(reloaded.listEnvironments()).extracting(Environment::getId).containsExactly("dev", "prod");
        reloaded.destroy();
    }

    @Test
    void deletingActiveEnvironmentActivatesNextOne() {
        service.addEnvironment(env("Dev"));
        service.addEnvironment(env("Prod"));

        service.deleteEnvironment("dev");

        assertThat(service.getActiveEnvironment()).map(Environment::getId).contains("prod");
        service.deleteEnvironment("prod");
        assertThat(service.getActiveEnvironment()).isEmpty();
    }

    @Test
    void updateKeepsPasswordWhenNotSentAndRecreatesTemplates() {
        service.addEnvironment(env("Dev"));
        var before = service.getJdbcTemplate("dev");
        assertThat(service.getJdbcTemplate("dev")).isSameAs(before);
        assertThat(service.getTransactionTemplate("dev")).isSameAs(service.getTransactionTemplate("dev"));

        service.updateEnvironment("dev", Environment.builder().name("Dev").jdbcUrl("jdbc:postgresql://other/db")
                .schemaName("app").build());

        Environment updated = service.getEnvironment("dev");
        assertThat(updated.getPassword()).isEqualTo("secret");
        assertThat(updated.getSchemaName()).isEqualTo("app");
        assertThat(service.getJdbcTemplate("dev")).isNotSameAs(before);
    }

    @Test
    void viewNeverContainsThePassword() {
        service.addEnvironment(env("Dev"));

        EnvironmentView view = service.getView("dev");

        assertThat(view.hasPassword()).isTrue();
        assertThat(view.active()).isTrue();
        assertThat(view.toString()).doesNotContain("secret");
    }

    @Test
    void validationErrors() {
        assertThatThrownBy(() -> service.addEnvironment(Environment.builder().name("x").jdbcUrl("jdbc:mysql://h/db").build()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.addEnvironment(Environment.builder().name(" ").jdbcUrl("jdbc:postgresql://h/db").build()))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.addEnvironment(env("Dev").toBuilder().colorBadge("red").build()))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.activateEnvironment("missing"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void readsHandWrittenPlainListFile() throws Exception {
        Files.writeString(dir.resolve("pgvc-environments.json"), """
                [ {"id": "dev", "name": "Dev", "jdbcUrl": "jdbc:postgresql://localhost/app", "username": "postgres",
                   "password": "p", "colorBadge": "#22c55e"} ]
                """);

        EnvironmentManagerService fromFile = newService();

        assertThat(fromFile.getEnvironment("dev").getSchemaName()).isEqualTo("public");
        assertThat(fromFile.getActiveEnvironment()).map(Environment::getId).contains("dev");
        fromFile.destroy();
    }

    @Test
    void connectionTestReportsUnreachableServer() {
        ConnectionTestResult result = service.testConnection(env("Nowhere"));

        assertThat(result.success()).isFalse();
        assertThat(result.message()).isNotBlank();
    }
}
