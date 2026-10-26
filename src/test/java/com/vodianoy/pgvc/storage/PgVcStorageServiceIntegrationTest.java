package com.vodianoy.pgvc.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vodianoy.pgvc.common.ApiException;
import com.vodianoy.pgvc.config.PgVcProperties;
import com.vodianoy.pgvc.environment.Environment;
import com.vodianoy.pgvc.environment.EnvironmentManagerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs against a real PostgreSQL 11+ server. Enabled when PGVC_TEST_JDBC_URL is set, e.g.
 * {@code PGVC_TEST_JDBC_URL=jdbc:postgresql://localhost:5432/postgres PGVC_TEST_USER=postgres PGVC_TEST_PASSWORD=...}.
 * The user must be a superuser; the test creates its own database and role.
 */
@EnabledIfEnvironmentVariable(named = "PGVC_TEST_JDBC_URL", matches = ".+")
class PgVcStorageServiceIntegrationTest {

    private static final String BASE_URL = System.getenv("PGVC_TEST_JDBC_URL");
    private static final String USER = envOr("PGVC_TEST_USER", "postgres");
    private static final String PASSWORD = envOr("PGVC_TEST_PASSWORD", "");
    private static final String DB = "pgvc_step1_it";
    private static final String DEV_ROLE = "pgvc_it_developer";

    @TempDir
    Path dir;

    private EnvironmentManagerService environments;
    private PgVcStorageService storage;

    private static String envOr(String name, String fallback) {
        String v = System.getenv(name);
        return v == null ? fallback : v;
    }

    private static String url(String db) {
        return BASE_URL.substring(0, BASE_URL.lastIndexOf('/') + 1) + db;
    }

    private static void sql(String db, String user, String password, String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(url(db), user, password); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private static void admin(String db, String sql) throws SQLException {
        sql(db, USER, PASSWORD, sql);
    }

    @BeforeEach
    void setUp() throws SQLException {
        String adminDb = BASE_URL.substring(BASE_URL.lastIndexOf('/') + 1);
        admin(adminDb, "DROP DATABASE IF EXISTS " + DB);
        admin(adminDb, "CREATE DATABASE " + DB);
        admin(adminDb, "DROP ROLE IF EXISTS " + DEV_ROLE);
        admin(adminDb, "CREATE ROLE " + DEV_ROLE + " LOGIN PASSWORD 'developer-pw'");

        PgVcProperties props = new PgVcProperties();
        props.setEnvironmentsFile(dir.resolve("pgvc-environments.json").toString());
        environments = new EnvironmentManagerService(props, new ObjectMapper().registerModule(new JavaTimeModule()));
        environments.addEnvironment(Environment.builder().name("Dev").jdbcUrl(url(DB)).username(USER).password(PASSWORD).build());
        environments.addEnvironment(Environment.builder().name("Developer").jdbcUrl(url(DB)).username(DEV_ROLE)
                .password("developer-pw").build());
        storage = new PgVcStorageService(environments);
    }

    @AfterEach
    void tearDown() {
        environments.destroy();
    }

    @Test
    void connectionTestRunsSelectVersion() {
        var result = environments.testConnection("dev");

        assertThat(result.success()).isTrue();
        assertThat(result.version()).startsWith("PostgreSQL");
        assertThat(result.versionNumber()).isGreaterThanOrEqualTo(110000);
        assertThat(result.superuser()).isTrue();
        assertThat(environments.testConnection("developer").superuser()).isFalse();
    }

    @Test
    void initializeIsIdempotentAndInstallsEventTriggers() {
        PgVcStatus before = storage.getStatus("dev");
        assertThat(before.reachable()).isTrue();
        assertThat(before.initialized()).isFalse();
        assertThatThrownBy(() -> storage.getCommitGraph("dev"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        storage.initializeEnvironment("dev");
        PgVcStatus status = storage.initializeEnvironment("dev");

        assertThat(status.initialized()).isTrue();
        assertThat(status.ddlTriggerEnabled()).isTrue();
        assertThat(status.dropTriggerEnabled()).isTrue();
        assertThat(status.head()).isNull();
        assertThat(status.commitCount()).isZero();
        assertThat(storage.getHead("dev")).isEmpty();
        assertThat(storage.getCommitGraph("dev").commits()).isEmpty();
    }

    @Test
    void initializeRequiresSuperuser() {
        assertThatThrownBy(() -> storage.initializeEnvironment("developer"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).contains("SUPERUSER");
                });
        assertThat(storage.getStatus("dev").initialized()).isFalse();
    }

    @Test
    void initializeReenablesDisabledTriggers() throws SQLException {
        storage.initializeEnvironment("dev");
        admin(DB, "ALTER EVENT TRIGGER pgvc_ddl_trigger DISABLE");
        assertThat(storage.getStatus("dev").ddlTriggerEnabled()).isFalse();

        assertThat(storage.initializeEnvironment("dev").ddlTriggerEnabled()).isTrue();
    }

    @Test
    void ddlInAllUserSchemasIsLoggedButNotPgvcOrTemporaryObjects() throws SQLException {
        storage.initializeEnvironment("dev");
        storage.clearAuditLog("dev");

        admin(DB, """
                CREATE SCHEMA sales;
                CREATE SCHEMA billing;
                CREATE TABLE sales.orders(id int PRIMARY KEY);
                CREATE TABLE billing.invoices(id int);
                CREATE TABLE public.settings(key text);
                CREATE TEMP TABLE scratch(id int);
                DROP TABLE scratch;
                CREATE TABLE _pgvc.internal_scratch(id int);
                DROP TABLE _pgvc.internal_scratch;
                ALTER TABLE billing.invoices ADD COLUMN total numeric;
                DROP TABLE billing.invoices;
                """);

        List<AuditLogEntry> log = storage.readAuditLog("dev");

        assertThat(log).extracting(AuditLogEntry::objectIdentity)
                .containsExactly("sales", "billing", "sales.orders", "sales.orders_pkey", "billing.invoices",
                        "public.settings", "billing.invoices", "billing.invoices");
        assertThat(log).extracting(AuditLogEntry::eventType).last().isEqualTo("sql_drop");
        assertThat(log).extracting(AuditLogEntry::commandTag).contains("CREATE SCHEMA", "CREATE TABLE",
                "ALTER TABLE", "DROP TABLE");
        assertThat(log).allSatisfy(e -> {
            assertThat(e.rawQuery()).isNotBlank();
            assertThat(e.dbUser()).isEqualTo(USER);
            assertThat(e.executedAt()).isNotNull();
        });
        assertThat(storage.getStatus("dev").pendingDdlCount()).isEqualTo(log.size());
    }

    @Test
    void ddlByNonSuperuserWorksAndIsLoggedWithTheRealUser() throws SQLException {
        storage.initializeEnvironment("dev");
        admin(DB, "CREATE SCHEMA team AUTHORIZATION " + DEV_ROLE);
        storage.clearAuditLog("dev");

        sql(DB, DEV_ROLE, "developer-pw", "CREATE TABLE team.tasks(id int); DROP TABLE team.tasks;");

        assertThat(storage.readAuditLog("dev"))
                .extracting(AuditLogEntry::eventType, AuditLogEntry::objectIdentity, AuditLogEntry::dbUser)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("ddl_command_end", "team.tasks", DEV_ROLE),
                        org.assertj.core.groups.Tuple.tuple("sql_drop", "team.tasks", DEV_ROLE));
        assertThatThrownBy(() -> sql(DB, DEV_ROLE, "developer-pw", "DELETE FROM _pgvc.ddl_audit_log"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void clearAuditLogUpToIdKeepsLaterEntries() throws SQLException {
        storage.initializeEnvironment("dev");
        storage.clearAuditLog("dev");
        admin(DB, "CREATE TABLE public.a(id int); CREATE TABLE public.b(id int);");
        List<AuditLogEntry> log = storage.readAuditLog("dev");

        assertThat(storage.clearAuditLog("dev", log.get(0).id())).isEqualTo(1);
        assertThat(storage.readAuditLog("dev")).extracting(AuditLogEntry::objectIdentity).containsExactly("public.b");
        assertThat(storage.clearAuditLog("dev")).isEqualTo(1);
        assertThat(storage.readAuditLog("dev")).isEmpty();
    }

    @Test
    void commitGraphAndHeadAreReadFromPgvcTables() throws SQLException {
        storage.initializeEnvironment("dev");
        admin(DB, """
                INSERT INTO _pgvc.commits (hash, parent_hash, author, message, created_at, up_sql, down_sql, schema_snapshot)
                VALUES ('aaa', NULL, 'alice', 'first', '2026-01-01T10:00:00Z', 'CREATE TABLE t(id int);', 'DROP TABLE t;', '{}'),
                       ('bbb', 'aaa', 'bob', 'second', '2026-01-02T10:00:00Z', 'ALTER TABLE t ADD c int;', 'ALTER TABLE t DROP c;', '{}');
                INSERT INTO _pgvc.refs (ref_name, commit_hash) VALUES ('HEAD', 'bbb');
                """);

        CommitGraph graph = storage.getCommitGraph("dev");

        assertThat(graph.head()).isEqualTo("bbb");
        assertThat(graph.commits()).extracting(CommitNode::hash).containsExactly("bbb", "aaa");
        assertThat(graph.commits().get(0).parentHash()).isEqualTo("aaa");
        assertThat(graph.commits().get(1).createdAt().toInstant()).hasToString("2026-01-01T10:00:00Z");
        assertThat(storage.getHead("dev")).contains("bbb");
        assertThat(storage.getStatus("dev").commitCount()).isEqualTo(2);
    }
}
