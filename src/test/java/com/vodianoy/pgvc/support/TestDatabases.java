package com.vodianoy.pgvc.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vodianoy.pgvc.config.PgVcProperties;
import com.vodianoy.pgvc.diff.SchemaDiffEngine;
import com.vodianoy.pgvc.environment.Environment;
import com.vodianoy.pgvc.environment.EnvironmentManagerService;
import com.vodianoy.pgvc.introspection.PostgresSchemaIntrospector;
import com.vodianoy.pgvc.migration.OfflineMigrationService;
import com.vodianoy.pgvc.storage.PgVcStorageService;
import com.vodianoy.pgvc.vcs.CommitHistory;
import com.vodianoy.pgvc.vcs.VersionControlService;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Real-PostgreSQL helpers for integration tests (PGVC_TEST_JDBC_URL / PGVC_TEST_USER / PGVC_TEST_PASSWORD). */
public final class TestDatabases {

    public static final String BASE_URL = System.getenv("PGVC_TEST_JDBC_URL");
    public static final String USER = System.getenv().getOrDefault("PGVC_TEST_USER", "postgres");
    public static final String PASSWORD = System.getenv().getOrDefault("PGVC_TEST_PASSWORD", "");

    private TestDatabases() {
    }

    /** Environment id the {@link Services} give to a database (slug of its name). */
    public static String envId(String db) {
        return db.toLowerCase().replaceAll("[^a-z0-9]+", "-");
    }

    public static String url(String db) {
        return BASE_URL.substring(0, BASE_URL.lastIndexOf('/') + 1) + db;
    }

    public static void recreate(String db) throws SQLException {
        String admin = BASE_URL.substring(BASE_URL.lastIndexOf('/') + 1);
        sql(admin, "DROP DATABASE IF EXISTS " + db);
        sql(admin, "CREATE DATABASE " + db);
    }

    public static void sql(String db, String sql) throws SQLException {
        sqlAs(db, USER, PASSWORD, sql);
    }

    public static void sqlAs(String db, String user, String password, String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(url(db), user, password); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    public static String query(String db, String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(url(db), USER, PASSWORD); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    public static String fixture(String name) throws IOException {
        return new ClassPathResource("fixtures/" + name).getContentAsString(StandardCharsets.UTF_8);
    }

    public static ObjectMapper objectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /** All services wired by hand (no Spring context), with one environment per database name. */
    public static final class Services implements AutoCloseable {
        public final EnvironmentManagerService environments;
        public final PgVcStorageService storage;
        public final PostgresSchemaIntrospector introspector;
        public final SchemaDiffEngine engine = new SchemaDiffEngine();
        public final CommitHistory history;
        public final VersionControlService vc;
        public final OfflineMigrationService offline;

        public Services(Path dir, String... databases) {
            PgVcProperties props = new PgVcProperties();
            props.setEnvironmentsFile(dir.resolve("pgvc-environments.json").toString());
            environments = new EnvironmentManagerService(props, objectMapper());
            for (String db : databases) {
                environments.addEnvironment(Environment.builder().name(db).jdbcUrl(url(db)).username(USER)
                        .password(PASSWORD).build());
            }
            storage = new PgVcStorageService(environments);
            introspector = new PostgresSchemaIntrospector(environments);
            history = new CommitHistory(storage);
            vc = new VersionControlService(environments, storage, introspector, engine, objectMapper(), history);
            offline = new OfflineMigrationService(environments, storage, history);
        }

        @Override
        public void close() {
            environments.destroy();
        }
    }
}
