package com.vodianoy.pgvc.safety;

import com.vodianoy.pgvc.config.I18nConfig;
import com.vodianoy.pgvc.diff.ChangeItem;
import com.vodianoy.pgvc.diff.SchemaDiffEngine;
import com.vodianoy.pgvc.diff.SchemaDiffResult;
import com.vodianoy.pgvc.execution.SqlScripts;
import com.vodianoy.pgvc.model.schema.ColumnModel;
import com.vodianoy.pgvc.model.schema.ConstraintModel;
import com.vodianoy.pgvc.model.schema.ConstraintType;
import com.vodianoy.pgvc.model.schema.DatabaseSnapshot;
import com.vodianoy.pgvc.model.schema.IndexModel;
import com.vodianoy.pgvc.model.schema.TableModel;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.9.1: the DOWN SQL of a change gets a zero-downtime form too - re-adding a dropped FK / CHECK as NOT VALID plus
 * VALIDATE CONSTRAINT in its own step, re-creating a dropped index CONCURRENTLY.
 */
class ZeroDowntimeDownPatternTest {

    private final MigrationSafetyAnalyzer analyzer = new MigrationSafetyAnalyzer(new I18nConfig().messageSource());
    private final SchemaDiffEngine engine = new SchemaDiffEngine();

    private static ColumnModel col(int attnum, String name, String type) {
        return ColumnModel.builder().attnum(attnum).name(name).dataType(type).nullable(true).build();
    }

    private static TableModel table(long oid, String name, boolean partitioned, ColumnModel... columns) {
        Map<String, ColumnModel> cols = new LinkedHashMap<>();
        for (ColumnModel c : columns) {
            cols.put(c.getName(), c);
        }
        return TableModel.builder().oid(oid).schemaName("scada").name(name).columns(cols).partitioned(partitioned)
                .partitionKeyDef(partitioned ? "RANGE (id)" : null)
                .estimatedRows(10).totalSizeBytes(16_384).prettySize("16 kB").build();
    }

    private static DatabaseSnapshot snapshot(TableModel... tables) {
        Map<String, TableModel> t = new LinkedHashMap<>();
        for (TableModel x : tables) {
            t.put(x.getQualifiedName(), x);
        }
        return DatabaseSnapshot.builder().sourceDatabaseId("sys:1@dev").serverVersion(160000).schemas(List.of("scada"))
                .tables(t).build();
    }

    /** HEAD: orders with an FK to customers, a CHECK and an index; customers. */
    private static DatabaseSnapshot head(boolean partitionedOrders) {
        TableModel customers = table(1, "customers", false, col(1, "id", "integer"));
        TableModel orders = table(2, "orders", partitionedOrders, col(1, "id", "integer"), col(2, "customer_id", "integer"),
                col(3, "amount", "numeric"));
        orders.getConstraints().put("orders_customer_fk", ConstraintModel.builder().oid(30).name("orders_customer_fk")
                .type(ConstraintType.FOREIGN_KEY).definition("FOREIGN KEY (customer_id) REFERENCES scada.customers(id)")
                .validated(true).signature("fk").build());
        orders.getConstraints().put("orders_amount_check", ConstraintModel.builder().oid(31).name("orders_amount_check")
                .type(ConstraintType.CHECK).definition("CHECK ((amount >= (0)::numeric))").validated(true).signature("ck").build());
        orders.getIndexes().put("orders_customer_idx", IndexModel.builder().oid(32).name("orders_customer_idx")
                .definition("CREATE INDEX orders_customer_idx ON scada.orders USING btree (customer_id)").signature("ix").build());
        return snapshot(customers, orders);
    }

    /** Live: the developer dropped the FK, the CHECK and the index. */
    private static DatabaseSnapshot live(boolean partitionedOrders) {
        TableModel customers = table(1, "customers", false, col(1, "id", "integer"));
        TableModel orders = table(2, "orders", partitionedOrders, col(1, "id", "integer"), col(2, "customer_id", "integer"),
                col(3, "amount", "numeric"));
        return snapshot(customers, orders);
    }

    private static ChangeItem change(SchemaDiffResult r, String objectName) {
        return r.getChanges().stream().filter(c -> c.getObjectName().equals(objectName)).findFirst().orElseThrow();
    }

    @Test
    void downOfDroppedConstraintsAndIndexesGetsTheZeroDowntimeForm() {
        DatabaseSnapshot head = head(false);
        DatabaseSnapshot live = live(false);
        SchemaDiffResult r = engine.diff(head, live);
        analyzer.annotate(r, MigrationSafetyAnalyzer.withSizes(head, live), live);

        ChangeItem fk = change(r, "scada.orders.orders_customer_fk");
        assertThat(fk.getDownSql()).contains("ALTER TABLE scada.orders ADD CONSTRAINT orders_customer_fk FOREIGN KEY");
        assertThat(fk.getZeroDowntimeDownSql()).isEqualTo(
                "ALTER TABLE scada.orders ADD CONSTRAINT orders_customer_fk FOREIGN KEY (customer_id) REFERENCES scada.customers(id)"
                        + " NOT VALID;\n" + SqlScripts.STEP_MARKER + "\nALTER TABLE scada.orders VALIDATE CONSTRAINT orders_customer_fk;");
        assertThat(fk.getDownAlternatives()).singleElement()
                .satisfies(a -> assertThat(r.getDownSql()).contains(a.statement()));

        ChangeItem check = change(r, "scada.orders.orders_amount_check");
        assertThat(check.getZeroDowntimeDownSql()).contains("CHECK ((amount >= (0)::numeric)) NOT VALID;")
                .contains("VALIDATE CONSTRAINT orders_amount_check;");

        ChangeItem index = change(r, "scada.orders_customer_idx");
        assertThat(index.getZeroDowntimeDownSql()).isEqualTo(SqlScripts.STEP_MARKER
                + "\nCREATE INDEX CONCURRENTLY orders_customer_idx ON scada.orders USING btree (customer_id);\n"
                + SqlScripts.STEP_MARKER);

        // every replaced statement is found in the DOWN script of the whole working tree ("Use in Editor")
        for (ChangeItem c : r.getChanges()) {
            c.getDownAlternatives().forEach(a -> assertThat(r.getDownSql()).contains(a.statement()));
        }
    }

    @Test
    void newTablesAndPartitionedTablesKeepThePlainDown() {
        // partitioned: neither NOT VALID foreign keys nor CREATE INDEX CONCURRENTLY are supported
        DatabaseSnapshot head = head(true);
        DatabaseSnapshot live = live(true);
        SchemaDiffResult r = engine.diff(head, live);
        analyzer.annotate(r, head, live);
        assertThat(r.getChanges()).allSatisfy(c -> assertThat(c.getZeroDowntimeDownSql()).isNull());

        // the developer created the table: its DOWN only drops it
        SchemaDiffResult created = engine.diff(snapshot(), head(false));
        analyzer.annotate(created, snapshot(), head(false));
        assertThat(created.getChanges()).allSatisfy(c -> assertThat(c.getZeroDowntimeDownSql()).isNull());
    }

    @Test
    void alreadySafeStatementsAreLeftAlone() {
        DatabaseSnapshot schema = live(false);
        SchemaDiffResult r = SchemaDiffResult.builder().changes(new java.util.ArrayList<>(List.of(
                ChangeItem.builder().objectName("a").downSql("CREATE INDEX CONCURRENTLY i ON scada.orders (id);").build(),
                ChangeItem.builder().objectName("b").downSql("ALTER TABLE scada.orders ADD CONSTRAINT c FOREIGN KEY (customer_id)"
                        + " REFERENCES scada.customers(id) NOT VALID;").build(),
                ChangeItem.builder().objectName("c").downSql("ALTER TABLE scada.orders ADD CONSTRAINT u UNIQUE (id);").build())))
                .build();
        analyzer.annotate(r, schema, schema);
        assertThat(r.getChanges().get(0).getZeroDowntimeDownSql()).isNull();
        assertThat(r.getChanges().get(1).getZeroDowntimeDownSql()).isNull();
        // a UNIQUE constraint keeps the analyser's own pattern (unique index CONCURRENTLY + USING INDEX) when it has one
        assertThat(r.getChanges().get(2).getZeroDowntimeDownSql()).satisfiesAnyOf(
                s -> assertThat(s).isNull(), s -> assertThat(s).contains("CREATE UNIQUE INDEX CONCURRENTLY"));
    }
}
