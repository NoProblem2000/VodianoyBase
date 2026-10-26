package com.vodianoy.pgvc.introspection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vodianoy.pgvc.common.ApiException;
import com.vodianoy.pgvc.config.PgVcProperties;
import com.vodianoy.pgvc.environment.Environment;
import com.vodianoy.pgvc.environment.EnvironmentManagerService;
import com.vodianoy.pgvc.model.schema.ColumnModel;
import com.vodianoy.pgvc.model.schema.ConstraintModel;
import com.vodianoy.pgvc.model.schema.ConstraintType;
import com.vodianoy.pgvc.model.schema.DatabaseSnapshot;
import com.vodianoy.pgvc.model.schema.SequenceModel;
import com.vodianoy.pgvc.model.schema.TableModel;
import com.vodianoy.pgvc.model.schema.ViewDependency;
import com.vodianoy.pgvc.model.schema.ViewModel;
import com.vodianoy.pgvc.storage.PgVcStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Introspection of the fixture src/test/resources/fixtures/rich-schema.sql on a real PostgreSQL 11+ server.
 * Enabled when PGVC_TEST_JDBC_URL is set (see README).
 */
@EnabledIfEnvironmentVariable(named = "PGVC_TEST_JDBC_URL", matches = ".+")
class PostgresSchemaIntrospectorIntegrationTest {

    private static final String BASE_URL = System.getenv("PGVC_TEST_JDBC_URL");
    private static final String USER = System.getenv().getOrDefault("PGVC_TEST_USER", "postgres");
    private static final String PASSWORD = System.getenv().getOrDefault("PGVC_TEST_PASSWORD", "");
    private static final String DB = "pgvc_step2_it";

    @TempDir
    Path dir;

    private EnvironmentManagerService environments;
    private PostgresSchemaIntrospector introspector;

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
        // objects of extensions must not be introspected (pg_stat_statements creates views in public)
        sql(DB, "CREATE EXTENSION IF NOT EXISTS pg_stat_statements");
        sql(DB, new ClassPathResource("fixtures/rich-schema.sql").getContentAsString(StandardCharsets.UTF_8));

        PgVcProperties props = new PgVcProperties();
        props.setEnvironmentsFile(dir.resolve("pgvc-environments.json").toString());
        environments = new EnvironmentManagerService(props, new ObjectMapper().registerModule(new JavaTimeModule()));
        environments.addEnvironment(Environment.builder().name("Dev").jdbcUrl(url(DB)).username(USER).password(PASSWORD).build());
        // the version-control schema must not be introspected either
        new PgVcStorageService(environments).initializeEnvironment("dev");
        introspector = new PostgresSchemaIntrospector(environments);
    }

    @AfterEach
    void tearDown() {
        environments.destroy();
    }

    @Test
    void listsAllUserSchemasOnly() {
        assertThat(introspector.listSchemas("dev")).containsExactly("billing", "public", "sales");
    }

    @Test
    void snapshotMetadataAndExclusions() {
        DatabaseSnapshot s = introspector.introspect("dev");

        assertThat(s.getSchemas()).containsExactly("billing", "public", "sales");
        assertThat(s.getServerVersion()).isGreaterThanOrEqualTo(110000);
        assertThat(s.getSourceDatabaseId()).matches(".+:\\d+");
        assertThat(s.getTimestamp()).isNotNull();
        assertThat(s.getTables()).containsOnlyKeys("billing.events", "billing.events_2026_01", "billing.events_2026_q2",
                "billing.events_2026_04", "billing.events_default", "billing.reservations", "public.Mixed Case",
                "sales.customers", "sales.orders");
        assertThat(s.getViews()).containsOnlyKeys("billing.mv_totals", "sales.v_big_orders", "sales.v_orders");
        assertThat(s.getSequences()).containsOnlyKeys("sales.invoice_no", "sales.orders_id_seq");
    }

    @Test
    void tablesAndPartitions() {
        DatabaseSnapshot s = introspector.introspect("dev");

        TableModel events = s.getTables().get("billing.events");
        assertThat(events.isPartitioned()).isTrue();
        assertThat(events.getPartitionKeyDef()).isEqualTo("RANGE (created_on)");
        assertThat(events.getParentTable()).isNull();
        assertThat(events.getOid()).isPositive();

        TableModel jan = s.getTables().get("billing.events_2026_01");
        assertThat(jan.isPartition()).isTrue();
        assertThat(jan.getParentTable()).isEqualTo("billing.events");
        assertThat(jan.getPartitionBoundExpr()).isEqualTo("FOR VALUES FROM ('2026-01-01') TO ('2026-02-01')");

        TableModel q2 = s.getTables().get("billing.events_2026_q2");
        assertThat(q2.isPartitioned()).isTrue();
        assertThat(q2.getParentTable()).isEqualTo("billing.events");
        assertThat(s.getTables().get("billing.events_2026_04").getParentTable()).isEqualTo("billing.events_2026_q2");
        assertThat(s.getTables().get("billing.events_default").getPartitionBoundExpr()).isEqualTo("DEFAULT");
        assertThat(s.getTables().get("sales.orders").isPartitioned()).isFalse();
    }

    @Test
    void columns() {
        DatabaseSnapshot s = introspector.introspect("dev");

        TableModel customers = s.getTables().get("sales.customers");
        assertThat(customers.getColumns().keySet()).containsExactly("id", "email", "name", "created_at", "score");
        ColumnModel id = customers.getColumns().get("id");
        assertThat(id.getAttnum()).isEqualTo(1);
        assertThat(id.getDataType()).isEqualTo("bigint");
        assertThat(id.isNullable()).isFalse();
        assertThat(id.getIdentityGeneration()).isEqualTo("ALWAYS");
        assertThat(id.getDefaultValue()).isNull();
        assertThat(customers.getColumns().get("name").getDataType()).isEqualTo("character varying(100)");
        assertThat(customers.getColumns().get("name").getCollation()).isEqualTo("\"C\"");
        assertThat(customers.getColumns().get("created_at").getDefaultValue()).isEqualTo("now()");
        assertThat(customers.getColumns().get("score").getDataType()).isEqualTo("numeric(5,2)");

        TableModel orders = s.getTables().get("sales.orders");
        assertThat(orders.getColumns().get("id").getDefaultValue()).isEqualTo("nextval('sales.orders_id_seq'::regclass)");
        assertThat(orders.getColumns().get("amount").isNullable()).isFalse();
        assertThat(orders.getColumns().get("status").getDefaultValue()).isEqualTo("'new'::text");

        assertThat(s.getTables().get("billing.events_2026_01").getColumns().values())
                .allSatisfy(c -> assertThat(c.isInherited()).isTrue());
        assertThat(s.getTables().get("public.Mixed Case").getColumns()).containsOnlyKeys("Select", "order");
    }

    @Test
    void generatedColumnsOnPostgres12AndNewer() throws SQLException {
        DatabaseSnapshot before = introspector.introspect("dev");
        Assumptions.assumeTrue(before.getServerVersion() >= 120000, "generated columns need PostgreSQL 12+");
        sql(DB, "CREATE TABLE sales.lines (qty int, price numeric, total numeric GENERATED ALWAYS AS (qty * price) STORED)");

        ColumnModel total = introspector.introspect("dev").getTables().get("sales.lines").getColumns().get("total");

        assertThat(total.getGeneratedExpression()).contains("qty").contains("price");
        assertThat(total.getDefaultValue()).isNull();
    }

    @Test
    void constraints() {
        DatabaseSnapshot s = introspector.introspect("dev");

        assertThat(s.getTables().get("sales.customers").getConstraints().values())
                .extracting(ConstraintModel::getName, ConstraintModel::getType)
                .containsExactlyInAnyOrder(
                        tuple("customers_pkey", ConstraintType.PRIMARY_KEY),
                        tuple("customers_email_key", ConstraintType.UNIQUE),
                        tuple("customers_score_check", ConstraintType.CHECK));

        ConstraintModel fk = s.getTables().get("sales.orders").getConstraints().get("orders_customer_id_fkey");
        assertThat(fk.getType()).isEqualTo(ConstraintType.FOREIGN_KEY);
        assertThat(fk.getDefinition()).startsWith("FOREIGN KEY (customer_id) REFERENCES sales.customers(id)")
                .contains("ON DELETE CASCADE");
        assertThat(fk.getColumns()).containsExactly("customer_id");
        assertThat(fk.getReferencedTable()).isEqualTo("sales.customers");
        assertThat(fk.getReferencedColumns()).containsExactly("id");
        assertThat(fk.isDeferrable()).isTrue();
        assertThat(fk.isInitiallyDeferred()).isTrue();
        assertThat(fk.isValidated()).isTrue();
        assertThat(fk.getSignature()).hasSize(32);

        assertThat(s.getTables().get("billing.events").getConstraints().get("events_pkey").getColumns())
                .containsExactly("id", "created_on");
        assertThat(s.getTables().get("billing.reservations").getConstraints().get("reservations_no_overlap").getType())
                .isEqualTo(ConstraintType.EXCLUDE);
        // the PK of a partition comes from the parent and is created by PostgreSQL itself
        assertThat(s.getTables().get("billing.events_2026_01").getConstraints()).isEmpty();
    }

    @Test
    void indexesExcludeConstraintAndAttachedPartitionIndexes() {
        DatabaseSnapshot s = introspector.introspect("dev");

        assertThat(s.getTables().get("sales.orders").getIndexes()).containsOnlyKeys("orders_open_status_idx",
                "orders_note_lower_idx");
        assertThat(s.getTables().get("sales.orders").getIndexes().get("orders_open_status_idx").getPredicate())
                .contains("status");
        assertThat(s.getTables().get("sales.orders").getIndexes().get("orders_note_lower_idx").getDefinition())
                .isEqualTo("CREATE INDEX orders_note_lower_idx ON sales.orders USING btree (lower(note))");
        assertThat(s.getTables().get("sales.customers").getIndexes()).isEmpty();
        assertThat(s.getTables().get("billing.reservations").getIndexes()).isEmpty();
        assertThat(s.getTables().get("billing.events").getIndexes()).containsOnlyKeys("events_kind_idx");
        assertThat(s.getTables().get("billing.events_2026_01").getIndexes()).containsOnlyKeys("events_2026_01_payload_idx");
        assertThat(s.getTables().get("billing.events_default").getIndexes()).isEmpty();

        ViewModel mv = s.getViews().get("billing.mv_totals");
        assertThat(mv.getIndexes()).containsOnlyKeys("mv_totals_customer_idx");
        assertThat(mv.getIndexes().get("mv_totals_customer_idx").isUnique()).isTrue();
        assertThat(mv.getIndexes().get("mv_totals_customer_idx").getMethod()).isEqualTo("btree");
    }

    @Test
    void sequences() {
        DatabaseSnapshot s = introspector.introspect("dev");

        SequenceModel invoice = s.getSequences().get("sales.invoice_no");
        assertThat(invoice.getDataType()).isEqualTo("integer");
        assertThat(invoice.getStartValue()).isEqualTo(1000);
        assertThat(invoice.getIncrementBy()).isEqualTo(5);
        assertThat(invoice.getMinValue()).isEqualTo(1);
        assertThat(invoice.getMaxValue()).isEqualTo(999999);
        assertThat(invoice.isCycle()).isTrue();
        assertThat(invoice.getOwnedByTable()).isNull();

        SequenceModel serial = s.getSequences().get("sales.orders_id_seq");
        assertThat(serial.getOwnedByTable()).isEqualTo("sales.orders");
        assertThat(serial.getOwnedByColumn()).isEqualTo("id");
    }

    @Test
    void viewsAndDependencies() {
        DatabaseSnapshot s = introspector.introspect("dev");

        ViewModel orders = s.getViews().get("sales.v_orders");
        assertThat(orders.isMaterialized()).isFalse();
        assertThat(orders.getDefinition()).contains("FROM").contains("JOIN");
        assertThat(orders.getDependencies())
                .extracting(ViewDependency::getRelation, ViewDependency::getRelationKind, ViewDependency::getColumn)
                .contains(tuple("sales.customers", "r", "email"), tuple("sales.orders", "r", "amount"));

        assertThat(s.getViews().get("sales.v_big_orders").getDependencies())
                .extracting(ViewDependency::getRelation, ViewDependency::getRelationKind)
                .containsOnly(tuple("sales.v_orders", "v"));

        ViewModel mv = s.getViews().get("billing.mv_totals");
        assertThat(mv.isMaterialized()).isTrue();
        assertThat(mv.isPopulated()).isTrue();
        assertThat(mv.getSignature()).hasSize(32);
    }

    @Test
    void schemaFilter() {
        DatabaseSnapshot s = introspector.introspect("dev", List.of("sales"));

        assertThat(s.getSchemas()).containsExactly("sales");
        assertThat(s.getTables()).containsOnlyKeys("sales.customers", "sales.orders");
        assertThat(s.getViews()).containsOnlyKeys("sales.v_big_orders", "sales.v_orders");

        assertThatThrownBy(() -> introspector.introspect("dev", List.of("sales", "nope", "_pgvc")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).contains("nope").contains("_pgvc");
                });
    }

    @Test
    void seesUncommittedDdlInsideAnOpenTransaction() {
        environments.getTransactionTemplate("dev").executeWithoutResult(tx -> {
            environments.getJdbcTemplate("dev").execute("CREATE TABLE sales.tx_probe (id int)");
            assertThat(introspector.introspect("dev").getTables()).containsKey("sales.tx_probe");
            tx.setRollbackOnly();
        });

        assertThat(introspector.introspect("dev").getTables()).doesNotContainKey("sales.tx_probe");
    }

    @Test
    void oidAttnumAndSignaturesSurviveRenames() throws SQLException {
        DatabaseSnapshot before = introspector.introspect("dev");
        sql(DB, "ALTER TABLE sales.orders RENAME COLUMN note TO remark; ALTER TABLE sales.orders RENAME TO purchases;");
        DatabaseSnapshot after = introspector.introspect("dev");

        TableModel was = before.getTables().get("sales.orders");
        TableModel now = after.getTables().get("sales.purchases");
        assertThat(now.getOid()).isEqualTo(was.getOid());
        assertThat(now.getColumns().get("remark").getAttnum()).isEqualTo(was.getColumns().get("note").getAttnum());
        assertThat(now.getIndexes().get("orders_note_lower_idx").getSignature())
                .isEqualTo(was.getIndexes().get("orders_note_lower_idx").getSignature());
        assertThat(now.getIndexes().get("orders_note_lower_idx").getDefinition()).contains("lower(remark)");
        assertThat(after.getViews().get("sales.v_orders").getSignature())
                .isEqualTo(before.getViews().get("sales.v_orders").getSignature());
    }

    @Test
    void snapshotSurvivesJsonRoundTrip() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        DatabaseSnapshot s = introspector.introspect("dev");

        String json = mapper.writeValueAsString(s);
        DatabaseSnapshot back = mapper.readValue(json, DatabaseSnapshot.class);

        assertThat(back).isEqualTo(s);
        assertThat(json).doesNotContain("qualifiedName").doesNotContain("\"partition\"");
    }
}
