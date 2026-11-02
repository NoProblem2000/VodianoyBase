package com.vodianoy.pgvc.diff;

import com.vodianoy.pgvc.execution.MigrationStep;
import com.vodianoy.pgvc.model.schema.DatabaseSnapshot;
import com.vodianoy.pgvc.model.schema.FunctionModel;
import com.vodianoy.pgvc.model.schema.TableModel;
import com.vodianoy.pgvc.model.schema.TriggerModel;
import com.vodianoy.pgvc.support.TestDatabases.Services;
import com.vodianoy.pgvc.vcs.CommitResult;
import com.vodianoy.pgvc.vcs.ExecuteMigrationRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static com.vodianoy.pgvc.support.TestDatabases.envId;
import static com.vodianoy.pgvc.support.TestDatabases.query;
import static com.vodianoy.pgvc.support.TestDatabases.recreate;
import static com.vodianoy.pgvc.support.TestDatabases.sql;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Functions, procedures, triggers, ENUM types, extensions, comments and grants on a real PostgreSQL: introspection,
 * and a round trip (the generated migration applied to another database makes the two identical).
 */
@EnabledIfEnvironmentVariable(named = "PGVC_TEST_JDBC_URL", matches = ".+")
class ExtendedObjectsIntegrationTest {

    private static final String SRC_DB = "pgvc_step11_src";
    private static final String DST_DB = "pgvc_step11_dst";

    /** Grants are compared, owners (same cluster anyway) and tablespaces are not; tmp_ objects are left out. */
    private static final DiffIgnoreRules RULES = new DiffIgnoreRules(false, true, false, false, List.of("tmp_%"));

    private static final String SCHEMA = """
            CREATE EXTENSION IF NOT EXISTS citext;
            CREATE SCHEMA shop;
            CREATE TYPE shop.order_status AS ENUM ('new', 'paid', 'cancelled');
            CREATE TABLE shop.orders (
                id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                email      citext NOT NULL,
                status     shop.order_status NOT NULL DEFAULT 'new',
                amount     numeric(12,2) NOT NULL DEFAULT 0,
                updated_at timestamptz
            );
            COMMENT ON TABLE shop.orders IS 'Customer orders';
            COMMENT ON COLUMN shop.orders.amount IS 'Gross amount, it''s with VAT';
            CREATE FUNCTION shop.touch_updated_at() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN
                NEW.updated_at := now();
                RETURN NEW;
            END $$;
            CREATE TRIGGER orders_touch BEFORE UPDATE ON shop.orders
                FOR EACH ROW EXECUTE PROCEDURE shop.touch_updated_at();
            CREATE FUNCTION shop.order_total(integer) RETURNS integer LANGUAGE sql AS $$ SELECT $1 * 2 $$;
            CREATE FUNCTION shop.order_total(integer, text) RETURNS integer LANGUAGE sql AS $$ SELECT $1 * 3 $$;
            COMMENT ON FUNCTION shop.order_total(integer) IS 'Doubles';
            CREATE PROCEDURE shop.archive(p_days integer) LANGUAGE plpgsql AS $$
            BEGIN
                DELETE FROM shop.orders WHERE updated_at < now() - make_interval(days => p_days);
            END $$;
            GRANT SELECT ON shop.orders TO pgvc_reader;
            REVOKE EXECUTE ON FUNCTION shop.order_total(integer, text) FROM PUBLIC;
            CREATE TABLE shop.tmp_import (line text);
            """;

    @TempDir
    Path dir;

    private Services s;
    private final String src = envId(SRC_DB);
    private final String dst = envId(DST_DB);

    @BeforeEach
    void setUp() throws Exception {
        recreate(SRC_DB);
        recreate(DST_DB);
        sql(SRC_DB, "DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pgvc_reader') THEN "
                + "CREATE ROLE pgvc_reader; END IF; END $$");
        sql(SRC_DB, SCHEMA);
        s = new Services(dir, SRC_DB, DST_DB);
    }

    @AfterEach
    void tearDown() {
        s.close();
    }

    @Test
    void introspectionReadsTheNewObjectKinds() {
        DatabaseSnapshot snap = s.introspector.introspect(src);

        assertThat(snap.getFormatVersion()).isEqualTo(DatabaseSnapshot.FORMAT_VERSION);
        assertThat(snap.getExtensions()).containsKey("citext").doesNotContainKey("plpgsql");
        assertThat(snap.getEnumTypes().get("shop.order_status").getValues()).containsExactly("new", "paid", "cancelled");
        assertThat(snap.getFunctions()).containsKeys("shop.touch_updated_at()", "shop.order_total(integer)",
                "shop.order_total(integer, text)");
        FunctionModel archive = snap.getFunctions().values().stream().filter(f -> f.getName().equals("archive"))
                .findFirst().orElseThrow();
        assertThat(archive.isProcedure()).isTrue();
        assertThat(archive.getResultType()).isNull();
        assertThat(snap.getFunctions().get("shop.order_total(integer)").getComment()).isEqualTo("Doubles");
        assertThat(snap.getFunctions().get("shop.order_total(integer)").getAcl()).containsExactly("PUBLIC=EXECUTE");
        assertThat(snap.getFunctions().get("shop.order_total(integer, text)").getAcl()).isEmpty();

        TableModel orders = snap.getTables().get("shop.orders");
        assertThat(orders.getComment()).isEqualTo("Customer orders");
        assertThat(orders.getColumns().get("amount").getComment()).isEqualTo("Gross amount, it's with VAT");
        assertThat(orders.getAcl()).contains("pgvc_reader=SELECT");
        assertThat(orders.getOwner()).isNotBlank();
        assertThat(orders.getTablespace()).isEqualTo("pg_default");
        TriggerModel trigger = orders.getTriggers().get("orders_touch");
        assertThat(trigger).isNotNull();
        assertThat(trigger.getEnabled()).isEqualTo("O");
        assertThat(trigger.getFunctionKey()).isEqualTo("shop.touch_updated_at()");
        assertThat(trigger.getDefinition()).contains("BEFORE UPDATE ON shop.orders");
        // the primary key / identity do not show up as triggers
        assertThat(orders.getTriggers()).hasSize(1);
    }

    @Test
    void generatedMigrationMakesAnotherDatabaseIdentical() throws Exception {
        s.storage.initializeEnvironment(dst);

        // ---- 1: everything is new in the target
        SchemaDiffResult first = s.engine.compare(s.introspector.introspect(dst), s.introspector.introspect(src), true, RULES);
        assertThat(first.getUpSql()).contains("CREATE EXTENSION IF NOT EXISTS \"citext\"", "CREATE TYPE shop.order_status",
                "CREATE OR REPLACE FUNCTION shop.touch_updated_at()", "CREATE TRIGGER orders_touch",
                "COMMENT ON TABLE shop.orders IS 'Customer orders';", "GRANT SELECT ON TABLE shop.orders TO pgvc_reader;",
                "REVOKE EXECUTE ON FUNCTION shop.order_total(integer, text) FROM PUBLIC;");
        assertThat(first.getUpSql()).doesNotContain("tmp_import");
        apply(first, "Initial schema");
        assertIdentical();
        assertThat(query(DST_DB, "SELECT count(*) FROM pg_trigger WHERE tgname = 'orders_touch'")).isEqualTo("1");

        // ---- 2: changes in the source
        sql(SRC_DB, "ALTER TYPE shop.order_status ADD VALUE 'shipped' AFTER 'paid'");
        sql(SRC_DB, """
                CREATE OR REPLACE FUNCTION shop.touch_updated_at() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    NEW.updated_at := clock_timestamp();
                    RETURN NEW;
                END $$;
                ALTER TABLE shop.orders DISABLE TRIGGER orders_touch;
                COMMENT ON COLUMN shop.orders.amount IS 'Net amount';
                DROP FUNCTION shop.order_total(integer);
                CREATE FUNCTION shop.order_total(integer) RETURNS numeric LANGUAGE sql AS $$ SELECT $1 * 2.5 $$;
                """);
        SchemaDiffResult second = s.engine.compare(s.introspector.introspect(dst), s.introspector.introspect(src), true, RULES);
        assertThat(second.getChanges()).extracting(ChangeItem::getObjectType)
                .contains(ObjectType.TYPE, ObjectType.FUNCTION, ObjectType.TRIGGER, ObjectType.COMMENT);
        assertThat(second.getUpSql()).contains("ALTER TYPE shop.order_status ADD VALUE IF NOT EXISTS 'shipped' AFTER 'paid';",
                "DROP FUNCTION shop.order_total(integer);", "ALTER TABLE shop.orders DISABLE TRIGGER orders_touch;",
                "COMMENT ON COLUMN shop.orders.amount IS 'Net amount';");
        assertThat(MigrationStep.isMultiStep(MigrationStep.plan(second.getUpSql()))).isTrue();
        CommitResult result = apply(second, "Enum value, functions, trigger state");
        assertThat(result.steps()).hasSizeGreaterThanOrEqualTo(2);
        assertIdentical();
        assertThat(query(DST_DB, "SELECT string_agg(enumlabel, ',' ORDER BY enumsortorder) FROM pg_enum"
                + " WHERE enumtypid = 'shop.order_status'::regtype")).isEqualTo("new,paid,shipped,cancelled");

        // ---- 3: the working tree of the target is clean with the default rules
        assertThat(s.vc.getWorkingTreeStatus(dst).diff().isHasChanges()).isFalse();
    }

    private CommitResult apply(SchemaDiffResult diff, String message) {
        return s.vc.executeAndCommitSql(dst, new ExecuteMigrationRequest(message, "test", diff.getUpSql(),
                diff.getDownSql(), true));
    }

    private void assertIdentical() {
        SchemaDiffResult rest = s.engine.compare(s.introspector.introspect(dst), s.introspector.introspect(src), true, RULES);
        assertThat(rest.getChanges()).as(rest.getUpSql()).isEmpty();
    }
}
