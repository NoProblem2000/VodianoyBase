package com.vodianoy.pgvc.vcs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vodianoy.pgvc.common.ApiException;
import com.vodianoy.pgvc.config.PgVcProperties;
import com.vodianoy.pgvc.diff.ChangeItem;
import com.vodianoy.pgvc.diff.CrossSchemaSequenceCheck;
import com.vodianoy.pgvc.diff.QuickFix;
import com.vodianoy.pgvc.diff.SchemaDiffEngine;
import com.vodianoy.pgvc.environment.DiscoveredDatabase;
import com.vodianoy.pgvc.environment.DiscoveredSchema;
import com.vodianoy.pgvc.environment.DiscoveryRequest;
import com.vodianoy.pgvc.environment.Environment;
import com.vodianoy.pgvc.environment.EnvironmentManagerService;
import com.vodianoy.pgvc.environment.JdbcUrls;
import com.vodianoy.pgvc.introspection.PostgresSchemaIntrospector;
import com.vodianoy.pgvc.model.schema.DatabaseSnapshot;
import com.vodianoy.pgvc.model.schema.TableModel;
import com.vodianoy.pgvc.storage.PgVcStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * v1.9.1 on a real PostgreSQL server (enabled when PGVC_TEST_JDBC_URL is set): the database / schema browser, tracked
 * schemas, the catalog-only size estimate, the faster working tree and the quick fix of a default that still uses the
 * sequence of another schema after {@code CREATE TABLE ... (LIKE ... INCLUDING ALL)}.
 */
@EnabledIfEnvironmentVariable(named = "PGVC_TEST_JDBC_URL", matches = ".+")
class FieldFeedbackIntegrationTest {

    private static final String BASE_URL = System.getenv("PGVC_TEST_JDBC_URL");
    private static final String USER = System.getenv().getOrDefault("PGVC_TEST_USER", "postgres");
    private static final String PASSWORD = System.getenv().getOrDefault("PGVC_TEST_PASSWORD", "");
    private static final String DB = "pgvc_v191_field";

    @TempDir
    Path dir;

    private EnvironmentManagerService environments;
    private PostgresSchemaIntrospector introspector;
    private VersionControlService vc;

    private static String url(String db) {
        return BASE_URL.substring(0, BASE_URL.lastIndexOf('/') + 1) + db;
    }

    private static void sql(String db, String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(url(db), USER, PASSWORD); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        String adminDb = BASE_URL.substring(BASE_URL.lastIndexOf('/') + 1);
        sql(adminDb, "DROP DATABASE IF EXISTS " + DB);
        sql(adminDb, "CREATE DATABASE " + DB);
        sql(adminDb, "COMMENT ON DATABASE " + DB + " IS 'SCADA field test'");
        sql(DB, """
                CREATE SCHEMA scada; COMMENT ON SCHEMA scada IS 'Mnemonic schemes';
                CREATE SCHEMA mdc;
                CREATE TABLE scada.mnemo_schemes (id serial PRIMARY KEY, title text NOT NULL);
                INSERT INTO scada.mnemo_schemes (title) SELECT 'scheme ' || g FROM generate_series(1, 2000) g;
                CREATE TABLE public.settings (key text PRIMARY KEY, value text);
                ANALYZE;
                """);

        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        PgVcProperties props = new PgVcProperties();
        props.setEnvironmentsFile(dir.resolve("pgvc-environments.json").toString());
        environments = new EnvironmentManagerService(props, mapper);
        JdbcUrls.Parts server = JdbcUrls.parse(BASE_URL);
        environments.addEnvironment(Environment.builder().name("Dev").host(server.host()).port(server.port()).databaseName(DB)
                .username(USER).password(PASSWORD).build());
        PgVcStorageService storage = new PgVcStorageService(environments);
        introspector = new PostgresSchemaIntrospector(environments);
        org.springframework.context.MessageSource messages = new com.vodianoy.pgvc.config.I18nConfig().messageSource();
        vc = new VersionControlService(environments, storage, introspector, new SchemaDiffEngine(), mapper,
                new CommitHistory(storage), new com.vodianoy.pgvc.execution.MigrationRunner(environments, props, messages),
                new com.vodianoy.pgvc.safety.MigrationSafetyAnalyzer(messages));
        storage.initializeEnvironment("dev");
        vc.commitWorkingTree("dev", new CommitRequest("Baseline", "alice", null, null, null));
    }

    @AfterEach
    void tearDown() {
        environments.destroy();
    }

    @Test
    void theBrowserListsDatabasesAndSchemasWithComments() {
        JdbcUrls.Parts server = JdbcUrls.parse(BASE_URL);
        List<DiscoveredDatabase> dbs = environments.discoverDatabases(
                new DiscoveryRequest(null, server.host(), server.port(), USER, PASSWORD, null, null));
        assertThat(dbs).extracting(DiscoveredDatabase::name).contains(DB).doesNotContain("template0", "template1");
        assertThat(dbs).filteredOn(d -> d.name().equals(DB)).singleElement().satisfies(d -> {
            assertThat(d.comment()).isEqualTo("SCADA field test");
            assertThat(d.canConnect()).isTrue();
            assertThat(d.prettySize()).isNotBlank();
        });

        // the stored password of the environment is used when none is sent
        List<DiscoveredSchema> schemas = environments.discoverSchemas(
                new DiscoveryRequest("dev", server.host(), server.port(), USER, null, DB, null));
        assertThat(schemas).extracting(DiscoveredSchema::name).contains("mdc", "public", "scada")
                .doesNotContain("_pgvc", "pg_catalog", "information_schema", "pg_toast");
        assertThat(schemas).filteredOn(s -> s.name().equals("scada")).singleElement().satisfies(s -> {
            assertThat(s.comment()).isEqualTo("Mnemonic schemes");
            assertThat(s.tableCount()).isGreaterThanOrEqualTo(2); // table + sequence
        });

        assertThatThrownBy(() -> environments.discoverSchemas(
                new DiscoveryRequest(null, server.host(), server.port(), USER, PASSWORD, "pgvc_no_such_db", null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("env.discoverFailed"));
    }

    @Test
    void sizesComeFromTheCatalogAndTheWorkingTreeIsTimed() {
        DatabaseSnapshot live = introspector.introspect("dev");
        TableModel t = live.getTables().get("scada.mnemo_schemes");
        assertThat(t.getEstimatedRows()).isEqualTo(2000);
        // relpages of the heap + the primary key index (both analysed above), 8 kB pages
        assertThat(t.getTotalSizeBytes()).isPositive().isEqualTo(t.getTotalSizeBytes() / 8192 * 8192);
        assertThat(t.getPrettySize()).isEqualTo(PostgresSchemaIntrospector.prettySize(t.getTotalSizeBytes()));

        WorkingTreeStatus wt = vc.getWorkingTreeStatus("dev", true);
        assertThat(wt.diff().isHasChanges()).isFalse();
        assertThat(wt.durationMs()).isGreaterThanOrEqualTo(wt.introspectionMs()).isNotNegative();
        assertThat(wt.snapshot().getTables()).containsKey("scada.mnemo_schemes");
        assertThat(vc.getWorkingTreeStatus("dev").snapshot()).isNull();
    }

    @Test
    void createTableLikeIsCaughtAndTheQuickFixRuns() throws Exception {
        sql(DB, "CREATE TABLE mdc.mnemo_schemes (LIKE scada.mnemo_schemes INCLUDING ALL);"
                + " INSERT INTO mdc.mnemo_schemes (title) VALUES ('copied');");

        WorkingTreeStatus wt = vc.getWorkingTreeStatus("dev");
        ChangeItem added = wt.diff().getChanges().stream().filter(c -> c.getObjectName().equals("mdc.mnemo_schemes"))
                .findFirst().orElseThrow();
        assertThat(added.getQuickFixes()).singleElement().satisfies(f -> {
            assertThat(f.objectName()).isEqualTo("mdc.mnemo_schemes.id");
            assertThat(f.message()).contains("scada.mnemo_schemes_id_seq");
        });
        assertThat(added.getUpSql()).contains("nextval('scada.mnemo_schemes_id_seq'::regclass)");

        QuickFixResult fixed = vc.applyQuickFix(new QuickFixRequest("dev", "mdc.mnemo_schemes.id", CrossSchemaSequenceCheck.KIND,
                null, null));
        assertThat(fixed.sql()).contains("SET DEFAULT nextval('mdc.mnemo_schemes_id_seq'::regclass)");

        DatabaseSnapshot live = introspector.introspect("dev");
        assertThat(live.getTables().get("mdc.mnemo_schemes").getColumns().get("id").getDefaultValue())
                .isEqualTo("nextval('mdc.mnemo_schemes_id_seq'::regclass)");
        assertThat(live.getSequences()).containsKey("mdc.mnemo_schemes_id_seq");
        List<QuickFix> left = CrossSchemaSequenceCheck.quickFixes(live);
        assertThat(left).isEmpty();
        // the new sequence continues after the copied row
        try (Connection c = DriverManager.getConnection(url(DB), USER, PASSWORD); Statement st = c.createStatement()) {
            st.execute("INSERT INTO mdc.mnemo_schemes (title) VALUES ('new')");
        }

        // the fix is computed by the server: an object without a problem cannot be "fixed"
        assertThatThrownBy(() -> vc.applyQuickFix(new QuickFixRequest("dev", "mdc.mnemo_schemes.id", null, null, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("quickFix.notFound"));
    }

    @Test
    void onlyTheTrackedSchemasAreVersionControlled() throws Exception {
        Environment dev = environments.getEnvironment("dev");
        environments.updateEnvironment("dev", dev.toBuilder().password(null).trackedSchemas(List.of("scada")).build());
        sql(DB, "CREATE TABLE mdc.untracked (id int); CREATE TABLE scada.tracked (id int);");

        WorkingTreeStatus wt = vc.getWorkingTreeStatus("dev");
        assertThat(wt.diff().getChanges()).extracting(ChangeItem::getObjectName)
                .contains("scada.tracked")
                .noneMatch(n -> n.startsWith("mdc.") || n.startsWith("public."));
        // the baseline recorded public.settings: not tracked any more, so it is not reported as dropped either
        assertThat(introspector.introspect("dev").getSchemas()).containsExactly("scada");

        environments.updateEnvironment("dev", environments.getEnvironment("dev").toBuilder().password(null)
                .trackedSchemas(List.of(Environment.ALL_USER_SCHEMAS)).build());
        assertThat(vc.getWorkingTreeStatus("dev").diff().getChanges()).extracting(ChangeItem::getObjectName)
                .contains("scada.tracked", "mdc.untracked");
    }
}
