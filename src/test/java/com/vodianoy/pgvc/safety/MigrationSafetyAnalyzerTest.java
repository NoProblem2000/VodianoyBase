package com.vodianoy.pgvc.safety;

import com.vodianoy.pgvc.config.I18nConfig;
import com.vodianoy.pgvc.diff.ChangeItem;
import com.vodianoy.pgvc.diff.SchemaDiffEngine;
import com.vodianoy.pgvc.diff.SchemaDiffResult;
import com.vodianoy.pgvc.execution.MigrationStep;
import com.vodianoy.pgvc.model.schema.ColumnModel;
import com.vodianoy.pgvc.model.schema.DatabaseSnapshot;
import com.vodianoy.pgvc.model.schema.IndexModel;
import com.vodianoy.pgvc.model.schema.TableModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationSafetyAnalyzerTest {

    private final MigrationSafetyAnalyzer analyzer = new MigrationSafetyAnalyzer(new I18nConfig().messageSource());

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    private static ColumnModel col(int attnum, String name, String type) {
        return ColumnModel.builder().attnum(attnum).name(name).dataType(type).nullable(true).build();
    }

    private static TableModel table(String name, long rows, long bytes, ColumnModel... columns) {
        Map<String, ColumnModel> cols = new LinkedHashMap<>();
        for (ColumnModel c : columns) {
            cols.put(c.getName(), c);
        }
        return TableModel.builder().oid(name.hashCode()).schemaName("public").name(name).columns(cols)
                .estimatedRows(rows).totalSizeBytes(bytes).prettySize(bytes / (1024 * 1024) + " MB").build();
    }

    /** orders: big (4.5M rows, 2 GB); customers: small with data; empty_t: empty. */
    private static DatabaseSnapshot schema(int serverVersion) {
        TableModel orders = table("orders", 4_500_000, 2048L * 1024 * 1024, col(1, "id", "integer"),
                col(2, "customer_id", "integer"), col(3, "code", "character varying(20)"), col(4, "amount", "numeric(10,2)"));
        orders.getIndexes().put("orders_code_idx", IndexModel.builder().name("orders_code_idx").build());
        TableModel customers = table("customers", 5_000, 2L * 1024 * 1024, col(1, "id", "integer"), col(2, "email", "text"));
        TableModel empty = table("empty_t", 0, 16 * 1024, col(1, "id", "integer"));
        Map<String, TableModel> tables = new LinkedHashMap<>();
        for (TableModel t : List.of(orders, customers, empty)) {
            tables.put(t.getQualifiedName(), t);
        }
        return DatabaseSnapshot.builder().sourceDatabaseId("db").serverVersion(serverVersion).schemas(List.of("public"))
                .tables(tables).build();
    }

    private SafetyReport one(String sql) {
        return one(sql, 160000);
    }

    private SafetyReport one(String sql, int serverVersion) {
        SqlSafetyReport r = analyzer.analyzeSql(sql, schema(serverVersion));
        assertThat(r.statements()).hasSize(1);
        return r.statements().get(0).report();
    }

    @Test
    void addNullableColumnIsABriefLock() {
        SafetyReport r = one("ALTER TABLE public.orders ADD COLUMN note text;");
        assertThat(r.getLockType()).isEqualTo(LockLevel.ACCESS_EXCLUSIVE);
        assertThat(r.isCausesTableRewrite()).isFalse();
        assertThat(r.isLongRunning()).isFalse();
        // brief ACCESS EXCLUSIVE on a big table: lock queue risk, not a long block
        assertThat(r.getRiskLevel()).isEqualTo(RiskLevel.WARNING);
        assertThat(r.getEstimatedRows()).isEqualTo(4_500_000);
        assertThat(one("ALTER TABLE customers ADD COLUMN note text;").getRiskLevel()).isEqualTo(RiskLevel.SAFE);
    }

    @Test
    void volatileDefaultRewritesStableDefaultDoesNot() {
        SafetyReport stable = one("ALTER TABLE orders ADD COLUMN created timestamptz DEFAULT now();");
        assertThat(stable.isCausesTableRewrite()).isFalse();
        SafetyReport volatileDefault = one("ALTER TABLE orders ADD COLUMN token uuid DEFAULT gen_random_uuid();");
        assertThat(volatileDefault.isCausesTableRewrite()).isTrue();
        assertThat(volatileDefault.getRiskLevel()).isEqualTo(RiskLevel.DANGEROUS);
        assertThat(volatileDefault.getEstimatedDurationHint()).contains("rewrite").contains("4.5M");
        // PostgreSQL 10 and older rewrote for any default
        assertThat(one("ALTER TABLE orders ADD COLUMN x int DEFAULT 0;", 100000).isCausesTableRewrite()).isTrue();
    }

    @Test
    void notNullColumnWithoutDefaultGetsTheCheckPattern() {
        SafetyReport r = one("ALTER TABLE customers ADD COLUMN phone text NOT NULL;");
        assertThat(r.getRiskLevel()).isEqualTo(RiskLevel.WARNING);
        assertThat(r.getZeroDowntimeAlternativeSql())
                .contains("ADD COLUMN phone text;")
                .contains("CHECK (phone IS NOT NULL) NOT VALID")
                .contains("-- pgvc:step")
                .contains("VALIDATE CONSTRAINT customers_phone_not_null")
                .contains("SET NOT NULL");
        assertThat(r.getAlternatives()).singleElement()
                .satisfies(a -> assertThat(a.statement()).isEqualTo("ALTER TABLE customers ADD COLUMN phone text NOT NULL;"));
        // the pattern itself is a valid multi-step script
        assertThat(MigrationStep.plan(r.getZeroDowntimeAlternativeSql())).hasSize(2);
        // PostgreSQL 11: no SET NOT NULL shortcut
        assertThat(one("ALTER TABLE customers ADD COLUMN phone text NOT NULL;", 110000).getZeroDowntimeAlternativeSql())
                .doesNotContain("SET NOT NULL");
    }

    @Test
    void typeChanges() {
        SafetyReport widen = one("ALTER TABLE orders ALTER COLUMN id TYPE bigint;");
        assertThat(widen.isCausesTableRewrite()).isTrue();
        assertThat(widen.isCausesDataLoss()).isFalse();
        assertThat(widen.getRiskLevel()).isEqualTo(RiskLevel.DANGEROUS);
        assertThat(widen.getNotes()).anyMatch(n -> n.contains("integer") && n.contains("bigint"));

        SafetyReport coercible = one("ALTER TABLE orders ALTER COLUMN code TYPE varchar(50);");
        assertThat(coercible.isCausesTableRewrite()).isFalse();
        assertThat(coercible.isCausesDataLoss()).isFalse();

        SafetyReport narrow = one("ALTER TABLE orders ALTER COLUMN code TYPE varchar(5);");
        assertThat(narrow.isCausesDataLoss()).isTrue();
        assertThat(narrow.getRiskLevel()).isEqualTo(RiskLevel.DANGEROUS);

        assertThat(MigrationSafetyAnalyzer.TypeChange.of("numeric(10,2)", "numeric(12,2)"))
                .isEqualTo(MigrationSafetyAnalyzer.TypeChange.COERCIBLE);
        assertThat(MigrationSafetyAnalyzer.TypeChange.of("bigint", "integer"))
                .isEqualTo(MigrationSafetyAnalyzer.TypeChange.NARROWING);
        assertThat(MigrationSafetyAnalyzer.TypeChange.of("character varying(20)", "text"))
                .isEqualTo(MigrationSafetyAnalyzer.TypeChange.COERCIBLE);
    }

    @Test
    void foreignKeyAndCheckGetNotValidPattern() {
        SafetyReport fk = one("ALTER TABLE orders ADD CONSTRAINT orders_customer_fk FOREIGN KEY (customer_id) REFERENCES customers (id);");
        assertThat(fk.getLockType()).isEqualTo(LockLevel.SHARE_ROW_EXCLUSIVE);
        assertThat(fk.getRiskLevel()).isEqualTo(RiskLevel.DANGEROUS);
        assertThat(fk.getZeroDowntimeAlternativeSql()).isEqualTo(
                "ALTER TABLE orders ADD CONSTRAINT orders_customer_fk FOREIGN KEY (customer_id) REFERENCES customers (id) NOT VALID;\n"
                        + "-- pgvc:step\nALTER TABLE orders VALIDATE CONSTRAINT orders_customer_fk;");
        SafetyReport notValid = one("ALTER TABLE orders ADD CONSTRAINT orders_customer_fk FOREIGN KEY (customer_id) REFERENCES customers (id) NOT VALID;");
        assertThat(notValid.isLongRunning()).isFalse();
        assertThat(notValid.getZeroDowntimeAlternativeSql()).isNull();
        SafetyReport validate = one("ALTER TABLE orders VALIDATE CONSTRAINT orders_customer_fk;");
        assertThat(validate.getLockType()).isEqualTo(LockLevel.SHARE_UPDATE_EXCLUSIVE);
        assertThat(validate.getRiskLevel()).isEqualTo(RiskLevel.SAFE);

        SafetyReport check = one("ALTER TABLE orders ADD CONSTRAINT amount_positive CHECK (amount > 0);");
        assertThat(check.getLockType()).isEqualTo(LockLevel.ACCESS_EXCLUSIVE);
        assertThat(check.getZeroDowntimeAlternativeSql()).contains("CHECK (amount > 0) NOT VALID;").contains("VALIDATE CONSTRAINT amount_positive");
    }

    @Test
    void indexesAndConcurrently() {
        SafetyReport plain = one("CREATE INDEX orders_amount_idx ON public.orders USING btree (amount);");
        assertThat(plain.getLockType()).isEqualTo(LockLevel.SHARE);
        assertThat(plain.getRiskLevel()).isEqualTo(RiskLevel.DANGEROUS);
        assertThat(plain.getZeroDowntimeAlternativeSql()).isEqualTo(
                "-- pgvc:step\nCREATE INDEX CONCURRENTLY orders_amount_idx ON public.orders USING btree (amount);\n-- pgvc:step");
        SafetyReport concurrently = one("CREATE INDEX CONCURRENTLY orders_amount_idx ON orders (amount);");
        assertThat(concurrently.getRiskLevel()).isEqualTo(RiskLevel.SAFE);
        assertThat(concurrently.isNonTransactional()).isTrue();
        assertThat(one("CREATE INDEX e_idx ON empty_t (id);").getRiskLevel()).isEqualTo(RiskLevel.SAFE);
        SafetyReport drop = one("DROP INDEX public.orders_code_idx;");
        assertThat(drop.getTable()).isEqualTo("public.orders");
        assertThat(drop.getZeroDowntimeAlternativeSql()).contains("DROP INDEX CONCURRENTLY public.orders_code_idx;");
    }

    @Test
    void commentsBeforeAStatementDoNotHideIt() {
        SqlSafetyReport r = analyzer.analyzeSql("-- UP SQL: template comment\n\nCREATE INDEX orders_amount_idx ON orders (amount);",
                schema(160000));
        assertThat(r.statements()).singleElement().satisfies(s -> {
            assertThat(s.sql()).isEqualTo("CREATE INDEX orders_amount_idx ON orders (amount);");
            assertThat(s.report().getZeroDowntimeAlternativeSql()).contains("CREATE INDEX CONCURRENTLY orders_amount_idx");
        });
    }

    @Test
    void dataLossIsDangerous() {
        assertThat(one("ALTER TABLE customers DROP COLUMN email;").isCausesDataLoss()).isTrue();
        assertThat(one("ALTER TABLE customers DROP COLUMN email;").getRiskLevel()).isEqualTo(RiskLevel.DANGEROUS);
        assertThat(one("DROP TABLE empty_t;").isCausesDataLoss()).isTrue();
        assertThat(one("TRUNCATE orders;").getRiskLevel()).isEqualTo(RiskLevel.DANGEROUS);
        assertThat(one("DELETE FROM orders;").isCausesDataLoss()).isTrue();
    }

    @Test
    void objectsCreatedByTheScriptAreSafe() {
        SqlSafetyReport r = analyzer.analyzeSql("""
                CREATE TABLE public.notes (id int, order_id int, body text NOT NULL);
                ALTER TABLE public.notes ADD CONSTRAINT notes_order_fk FOREIGN KEY (order_id) REFERENCES orders (id);
                CREATE INDEX notes_order_idx ON public.notes (order_id);
                ALTER TABLE public.notes ADD COLUMN extra text NOT NULL;
                """, schema(160000));
        assertThat(r.statements()).hasSize(4);
        assertThat(r.statements()).allMatch(s -> s.report().getRiskLevel() == RiskLevel.SAFE);
        assertThat(r.overall().getRiskLevel()).isEqualTo(RiskLevel.SAFE);
        assertThat(r.multiStep()).isFalse();
    }

    @Test
    void overallReportAndRussianTexts() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("ru"));
        SqlSafetyReport r = analyzer.analyzeSql("""
                ALTER TABLE customers ADD COLUMN note text;
                CREATE INDEX orders_amount_idx ON orders (amount);
                -- pgvc:step
                CREATE INDEX CONCURRENTLY customers_email_idx ON customers (email);
                """, schema(160000));
        assertThat(r.steps()).isEqualTo(2);
        assertThat(r.multiStep()).isTrue();
        assertThat(r.statements()).extracting(StatementSafety::step).containsExactly(1, 1, 2);
        assertThat(r.overall().getRiskLevel()).isEqualTo(RiskLevel.DANGEROUS);
        assertThat(r.overall().getLockType()).isEqualTo(LockLevel.ACCESS_EXCLUSIVE);
        assertThat(r.overall().getAlternatives()).hasSize(1);
        assertThat(r.statements().get(1).report().getEstimatedDurationHint()).contains("построение индекса");
        assertThat(r.statements().get(1).report().getNotes()).anyMatch(n -> n.contains("блокирует"));
    }

    @Test
    void annotateChangesOfADiff() {
        DatabaseSnapshot current = schema(160000);
        DatabaseSnapshot target = current.toBuilder().tables(new LinkedHashMap<>(current.getTables())).build();
        TableModel orders = current.getTables().get("public.orders");
        Map<String, ColumnModel> cols = new LinkedHashMap<>(orders.getColumns());
        cols.put("status", ColumnModel.builder().attnum(9).name("status").dataType("text").nullable(false).build());
        target.getTables().put("public.orders", orders.toBuilder().columns(cols).build());

        SchemaDiffResult diff = new SchemaDiffEngine().compare(current, target, true);
        analyzer.annotate(diff, current);
        ChangeItem status = diff.getChanges().stream().filter(c -> c.getObjectName().equals("public.orders.status"))
                .findFirst().orElseThrow();
        assertThat(status.getSafety()).isNotNull();
        assertThat(status.getSafety().getLockType()).isEqualTo(LockLevel.ACCESS_EXCLUSIVE);
        assertThat(status.getSafety().getRiskLevel()).isNotEqualTo(RiskLevel.SAFE);
        assertThat(status.getSafety().getAlternatives()).hasSize(1);
        assertThat(status.getSafety().getZeroDowntimeAlternativeSql()).contains("NOT VALID");
        assertThat(status.getSafety().getPrettySize()).isEqualTo("2048 MB");
    }

    @Test
    void prettySizeMatchesPostgres() {
        assertThat(com.vodianoy.pgvc.introspection.PostgresSchemaIntrospector.prettySize(8192)).isEqualTo("8192 bytes");
        assertThat(com.vodianoy.pgvc.introspection.PostgresSchemaIntrospector.prettySize(10240)).isEqualTo("10 kB");
        assertThat(com.vodianoy.pgvc.introspection.PostgresSchemaIntrospector.prettySize(1288490189L)).isEqualTo("1229 MB");
        assertThat(com.vodianoy.pgvc.introspection.PostgresSchemaIntrospector.prettySize(20L * 1024 * 1024 * 1024)).isEqualTo("20 GB");
    }
}
