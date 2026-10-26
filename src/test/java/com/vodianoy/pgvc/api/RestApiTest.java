package com.vodianoy.pgvc.api;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext
class RestApiTest {

    @Autowired
    private MockMvc mvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        Path dir = Files.createTempDirectory("pgvc-rest-test");
        registry.add("pgvc.environments-file", () -> dir.resolve("pgvc-environments.json").toString());
        registry.add("pgvc.connect-timeout-seconds", () -> "1");
    }

    private void add(String json) throws Exception {
        mvc.perform(post("/api/environments").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated());
    }

    @Test
    void environmentLifecycle() throws Exception {
        mvc.perform(post("/api/environments").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "Offline Dev", "jdbcUrl": "jdbc:postgresql://127.0.0.1:1/nope",
                         "username": "u", "password": "secret", "colorBadge": "#3B82F6"}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("offline-dev"))
                .andExpect(jsonPath("$.schemaName").value("public"))
                .andExpect(jsonPath("$.colorBadge").value("#3b82f6"))
                .andExpect(jsonPath("$.hasPassword").value(true))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.password").doesNotExist());

        add("""
                {"name": "Offline Prod", "jdbcUrl": "jdbc:postgresql://127.0.0.1:1/prod", "schemaName": "app"}
                """);

        mvc.perform(get("/api/environments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[1].schemaName").value("app"));

        mvc.perform(put("/api/environments/active").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"environmentId\": \"offline-prod\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
        mvc.perform(get("/api/environments/active"))
                .andExpect(jsonPath("$.id").value("offline-prod"));

        mvc.perform(put("/api/environments/offline-prod").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Offline Prod\", \"jdbcUrl\": \"jdbc:postgresql://127.0.0.1:1/prod2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jdbcUrl").value("jdbc:postgresql://127.0.0.1:1/prod2"));

        mvc.perform(post("/api/environments/offline-dev/test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false));

        mvc.perform(get("/api/environments/offline-dev/pgvc/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reachable").value(false))
                .andExpect(jsonPath("$.error").isNotEmpty());

        mvc.perform(get("/api/environments/offline-dev/pgvc/commits"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message", startsWith("Cannot connect")));

        mvc.perform(delete("/api/environments/offline-prod")).andExpect(status().isNoContent());
        mvc.perform(get("/api/environments/active")).andExpect(jsonPath("$.id").value("offline-dev"));
        mvc.perform(delete("/api/environments/offline-dev")).andExpect(status().isNoContent());
        mvc.perform(get("/api/environments/active")).andExpect(status().isNoContent());
    }

    @Test
    void validationAndNotFound() throws Exception {
        mvc.perform(post("/api/environments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"bad\", \"jdbcUrl\": \"jdbc:mysql://x/db\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("JDBC URL must start with jdbc:postgresql:"));

        mvc.perform(get("/api/environments/missing")).andExpect(status().isNotFound());
        mvc.perform(get("/api/environments/missing/pgvc/status")).andExpect(status().isNotFound());
        mvc.perform(put("/api/environments/active").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/environments").contentType(MediaType.APPLICATION_JSON).content("not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void initializeThroughRestAgainstRealDatabase() throws Exception {
        String base = System.getenv("PGVC_TEST_JDBC_URL");
        Assumptions.assumeTrue(base != null && !base.isBlank(), "PGVC_TEST_JDBC_URL is not set");
        String user = System.getenv().getOrDefault("PGVC_TEST_USER", "postgres");
        String password = System.getenv().getOrDefault("PGVC_TEST_PASSWORD", "");
        String url = base.substring(0, base.lastIndexOf('/') + 1) + "pgvc_step1_rest";
        try (Connection c = DriverManager.getConnection(base, user, password); Statement st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS pgvc_step1_rest");
            st.execute("CREATE DATABASE pgvc_step1_rest");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }

        add("{\"name\": \"Rest Db\", \"jdbcUrl\": \"" + url + "\", \"username\": \"" + user + "\", \"password\": \""
                + password + "\"}");

        mvc.perform(post("/api/environments/rest-db/test"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.version", startsWith("PostgreSQL")));
        mvc.perform(post("/api/environments/rest-db/pgvc/initialize"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.initialized").value(true))
                .andExpect(jsonPath("$.ddlTriggerEnabled").value(true))
                .andExpect(jsonPath("$.dropTriggerEnabled").value(true));
        mvc.perform(get("/api/environments/rest-db/pgvc/commits"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.head").isEmpty())
                .andExpect(jsonPath("$.commits", hasSize(0)));
        mvc.perform(get("/api/environments/rest-db/pgvc/head"))
                .andExpect(jsonPath("$.head").isEmpty());
        mvc.perform(get("/api/environments/rest-db/pgvc/audit-log")).andExpect(status().isOk());
        mvc.perform(delete("/api/environments/rest-db/pgvc/audit-log"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").isNumber());

        mvc.perform(delete("/api/environments/rest-db")).andExpect(status().isNoContent());
    }

    @Test
    void schemaSnapshotThroughRest() throws Exception {
        String base = System.getenv("PGVC_TEST_JDBC_URL");
        Assumptions.assumeTrue(base != null && !base.isBlank(), "PGVC_TEST_JDBC_URL is not set");
        String user = System.getenv().getOrDefault("PGVC_TEST_USER", "postgres");
        String password = System.getenv().getOrDefault("PGVC_TEST_PASSWORD", "");
        String url = base.substring(0, base.lastIndexOf('/') + 1) + "pgvc_step2_rest";
        try (Connection c = DriverManager.getConnection(base, user, password); Statement st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS pgvc_step2_rest");
            st.execute("CREATE DATABASE pgvc_step2_rest");
        }
        try (Connection c = DriverManager.getConnection(url, user, password); Statement st = c.createStatement()) {
            st.execute(new ClassPathResource("fixtures/rich-schema.sql").getContentAsString(StandardCharsets.UTF_8));
        }

        add("{\"name\": \"Schema Db\", \"jdbcUrl\": \"" + url + "\", \"username\": \"" + user + "\", \"password\": \""
                + password + "\"}");

        mvc.perform(get("/api/environments/schema-db/schema/schemas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", contains("billing", "public", "sales")));
        mvc.perform(get("/api/environments/schema-db/schema/snapshot").param("schemas", "sales"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemas", contains("sales")))
                .andExpect(jsonPath("$.timestamp", startsWith("20")))
                .andExpect(jsonPath("$.tables['sales.orders'].columns.amount.dataType").value("numeric(12,2)"))
                .andExpect(jsonPath("$.tables['sales.orders'].constraints.orders_customer_id_fkey.type").value("FOREIGN_KEY"))
                .andExpect(jsonPath("$.views['sales.v_orders'].materialized").value(false))
                .andExpect(jsonPath("$.sequences['sales.invoice_no'].incrementBy").value(5))
                .andExpect(jsonPath("$.tables['billing.events']").doesNotExist());
        mvc.perform(get("/api/environments/schema-db/schema/snapshot").param("schemas", "missing"))
                .andExpect(status().isBadRequest());

        mvc.perform(delete("/api/environments/schema-db")).andExpect(status().isNoContent());
    }
}
