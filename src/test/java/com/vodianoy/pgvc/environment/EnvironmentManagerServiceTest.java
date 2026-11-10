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

    // ------------------------------------------------------------------ v1.9.1: server / database / schemas

    @Test
    void hostPortAndDatabaseBuildTheJdbcUrl() {
        Environment saved = service.addEnvironment(Environment.builder().name("Scada").host(" db.local ").port(6432)
                .databaseName("scada_prod").username("u").trackedSchemas(java.util.List.of("scada", "mdc", "scada", " ")).build());

        assertThat(saved.getJdbcUrl()).isEqualTo("jdbc:postgresql://db.local:6432/scada_prod");
        assertThat(saved.getHost()).isEqualTo("db.local");
        assertThat(saved.getPort()).isEqualTo(6432);
        assertThat(saved.getDatabaseName()).isEqualTo("scada_prod");
        assertThat(saved.getTrackedSchemas()).containsExactly("scada", "mdc");
        assertThat(saved.explicitSchemas()).containsExactly("scada", "mdc");

        EnvironmentView view = service.getView(saved.getId());
        assertThat(view.host()).isEqualTo("db.local");
        assertThat(view.trackedSchemas()).containsExactly("scada", "mdc");

        // the parameters of the stored URL survive a change of host / database
        service.updateEnvironment(saved.getId(), saved.toBuilder().jdbcUrl("jdbc:postgresql://db.local:6432/scada_prod?sslmode=require")
                .host("").build());
        Environment withParams = service.updateEnvironment(saved.getId(), service.getEnvironment(saved.getId()).toBuilder()
                .databaseName("scada_test").trackedSchemas(null).build());
        assertThat(withParams.getJdbcUrl()).isEqualTo("jdbc:postgresql://db.local:6432/scada_test?sslmode=require");
        // not sent = kept
        assertThat(withParams.getTrackedSchemas()).containsExactly("scada", "mdc");
    }

    @Test
    void allUserSchemasIsTheDefaultAndOldFilesKeepWorking() throws Exception {
        Environment plain = service.addEnvironment(env("Dev"));
        assertThat(plain.getTrackedSchemas()).containsExactly(Environment.ALL_USER_SCHEMAS);
        assertThat(plain.explicitSchemas()).isNull();
        assertThat(plain.getHost()).isEqualTo("127.0.0.1");
        assertThat(plain.getPort()).isEqualTo(1);
        assertThat(plain.getDatabaseName()).isEqualTo("Dev");

        Environment all = service.addEnvironment(env("Qa").toBuilder()
                .trackedSchemas(java.util.List.of("scada", Environment.ALL_USER_SCHEMAS)).build());
        assertThat(all.getTrackedSchemas()).containsExactly(Environment.ALL_USER_SCHEMAS);

        // a file of v1.9.0: no host / port / database / trackedSchemas
        Files.writeString(dir.resolve("pgvc-environments.json"), """
                {"activeEnvironmentId": "dev", "environments": [ {"id": "dev", "name": "Dev",
                  "jdbcUrl": "jdbc:postgresql://pg.internal/app?ApplicationName=x", "username": "postgres", "password": "p"} ]}
                """);
        EnvironmentManagerService old = newService();
        Environment dev = old.getEnvironment("dev");
        assertThat(dev.getHost()).isEqualTo("pg.internal");
        assertThat(dev.getPort()).isEqualTo(5432);
        assertThat(dev.getDatabaseName()).isEqualTo("app");
        assertThat(dev.explicitSchemas()).isNull();
        old.destroy();
    }

    @Test
    void serverConnectionValidation() {
        assertThatThrownBy(() -> service.addEnvironment(Environment.builder().name("x").host("h").build()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("env.databaseRequired"));
        assertThatThrownBy(() -> service.addEnvironment(Environment.builder().name("x").host("h").port(70000).databaseName("d").build()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("env.portInvalid"));
        assertThatThrownBy(() -> service.addEnvironment(Environment.builder().name("x").build()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("env.hostRequired"));
        assertThatThrownBy(() -> service.addEnvironment(Environment.builder().name("x").host("h").databaseName("d")
                .trackedSchemas(java.util.List.of(" ")).build()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("env.schemasRequired"));
    }

    @Test
    void discoveryReportsAnUnreachableServer() {
        assertThatThrownBy(() -> service.discoverDatabases(new DiscoveryRequest(null, "127.0.0.1", 1, "postgres", "x", null, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("env.discoverFailed"));
        assertThatThrownBy(() -> service.discoverSchemas(new DiscoveryRequest(null, "127.0.0.1", 1, "postgres", "x", null, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("env.databaseRequired"));
    }

    @Test
    void jdbcUrlsAreTakenApartAndBuilt() {
        assertThat(JdbcUrls.parse("jdbc:postgresql://[::1]:6543/my%20db?ssl=true"))
                .isEqualTo(new JdbcUrls.Parts("::1", 6543, "my db", "ssl=true", false));
        assertThat(JdbcUrls.parse("jdbc:postgresql://a:5433,b:5434/app").multiHost()).isTrue();
        assertThat(JdbcUrls.parse("jdbc:postgresql:app")).isNull();
        assertThat(JdbcUrls.build("::1", null, "my db", null)).isEqualTo("jdbc:postgresql://[::1]:5432/my%20db");
        assertThat(JdbcUrls.withDatabase("jdbc:postgresql://a:5433,b:5434/app?targetServerType=primary", "postgres"))
                .isEqualTo("jdbc:postgresql://a:5433,b:5434/postgres?targetServerType=primary");
    }
}
