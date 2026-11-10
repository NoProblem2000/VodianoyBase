package com.vodianoy.pgvc.diff;

import com.vodianoy.pgvc.execution.MigrationStep;
import com.vodianoy.pgvc.execution.TransactionalScript;
import com.vodianoy.pgvc.model.schema.ColumnModel;
import com.vodianoy.pgvc.model.schema.DatabaseSnapshot;
import com.vodianoy.pgvc.model.schema.EnumTypeModel;
import com.vodianoy.pgvc.model.schema.ExtensionModel;
import com.vodianoy.pgvc.model.schema.FunctionModel;
import com.vodianoy.pgvc.model.schema.TableModel;
import com.vodianoy.pgvc.model.schema.TriggerModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Functions, triggers, ENUM types, extensions, comments, owners / grants / tablespaces and the ignore rules. */
class ExtendedObjectsDiffTest {

    private final SchemaDiffEngine engine = new SchemaDiffEngine(() -> DiffIgnoreRules.NONE);

    // ------------------------------------------------------------------ builders

    private static ColumnModel col(int attnum, String name, String type) {
        return ColumnModel.builder().attnum(attnum).name(name).dataType(type).nullable(true).build();
    }

    private static TableModel table(long oid, String name, ColumnModel... columns) {
        Map<String, ColumnModel> cols = new LinkedHashMap<>();
        for (ColumnModel c : columns) {
            cols.put(c.getName(), c);
        }
        return TableModel.builder().oid(oid).schemaName("public").name(name).columns(cols).build();
    }

    private static String fdef(String name, String args, String returns, String body) {
        return "CREATE OR REPLACE FUNCTION public." + name + "(" + args + ")\n RETURNS " + returns
                + "\n LANGUAGE plpgsql\nAS $function$" + body + "$function$\n";
    }

    private static FunctionModel function(long oid, String name, String args, String returns, String body) {
        return FunctionModel.builder().oid(oid).schemaName("public").name(name).identityArguments(args).kind("f")
                .resultType(returns).language("plpgsql").definition(fdef(name, args, returns, body)).build();
    }

    private static TriggerModel trigger(long oid, String name, String table, String function) {
        return TriggerModel.builder().oid(oid).name(name).enabled("O").functionKey("public." + function + "()")
                .definition("CREATE TRIGGER " + name + " BEFORE UPDATE ON public." + table
                        + " FOR EACH ROW EXECUTE PROCEDURE public." + function + "()")
                .build();
    }

    private static EnumTypeModel enumType(long oid, String name, String... values) {
        return EnumTypeModel.builder().oid(oid).schemaName("public").name(name).values(new ArrayList<>(List.of(values)))
                .build();
    }

    private static Builder db(String id) {
        return new Builder(id);
    }

    private static final class Builder {
        private final DatabaseSnapshot s;

        Builder(String id) {
            s = DatabaseSnapshot.builder().sourceDatabaseId(id).serverVersion(160000).schemas(List.of("public")).build();
        }

        Builder table(TableModel t) {
            s.getTables().put(t.getQualifiedName(), t);
            return this;
        }

        Builder function(FunctionModel f) {
            s.getFunctions().put(f.getKey(), f);
            return this;
        }

        Builder enumType(EnumTypeModel e) {
            s.getEnumTypes().put(e.getQualifiedName(), e);
            return this;
        }

        Builder extension(String name, String schema, String version) {
            s.getExtensions().put(name, ExtensionModel.builder().name(name).schemaName(schema).version(version).build());
            return this;
        }

        DatabaseSnapshot build() {
            return s;
        }
    }

    private static void assertOrdered(String sql, String... parts) {
        int last = -1;
        for (String p : parts) {
            int idx = sql.indexOf(p);
            assertTrue(idx >= 0, "missing: " + p + "\n" + sql);
            assertTrue(idx > last, "wrong order at: " + p + "\n" + sql);
            last = idx;
        }
    }

    private static ChangeItem only(SchemaDiffResult r, ChangeType type, ObjectType objectType) {
        List<ChangeItem> found = r.getChanges().stream()
                .filter(c -> c.getChangeType() == type && c.getObjectType() == objectType).toList();
        assertEquals(1, found.size(), "expected one " + type + " " + objectType + " in " + r.getChanges());
        return found.get(0);
    }

    // ------------------------------------------------------------------ functions

    @Test
    void changedFunctionBodyIsCreateOrReplace() {
        DatabaseSnapshot head = db("db1").function(function(5, "touch", "", "trigger", "BEGIN RETURN NEW; END")).build();
        DatabaseSnapshot live = db("db1").function(function(5, "touch", "", "trigger",
                "BEGIN NEW.updated_at := now(); RETURN NEW; END")).build();

        SchemaDiffResult r = engine.diff(head, live);

        ChangeItem item = only(r, ChangeType.MODIFIED, ObjectType.FUNCTION);
        assertEquals("public.touch()", item.getObjectName());
        assertTrue(item.getUpSql().startsWith("CREATE OR REPLACE FUNCTION public.touch()"), item.getUpSql());
        assertFalse(r.getUpSql().contains("DROP FUNCTION"), r.getUpSql());
        assertOrdered(r.getUpSql(), "SET LOCAL check_function_bodies = off;", "CREATE OR REPLACE FUNCTION");
        assertTrue(item.getDownSql().contains("BEGIN RETURN NEW; END"), item.getDownSql());
    }

    @Test
    void changedReturnTypeIsDropAndCreateAndRecreatesTriggers() {
        TableModel headTable = table(10, "orders", col(1, "id", "integer"));
        headTable.getTriggers().put("orders_touch", trigger(30, "orders_touch", "orders", "touch"));
        TableModel liveTable = table(10, "orders", col(1, "id", "integer"));
        liveTable.getTriggers().put("orders_touch", trigger(30, "orders_touch", "orders", "touch"));
        DatabaseSnapshot head = db("db1").table(headTable)
                .function(function(5, "touch", "", "integer", "BEGIN RETURN 1; END")).build();
        DatabaseSnapshot live = db("db1").table(liveTable)
                .function(function(5, "touch", "", "trigger", "BEGIN RETURN NEW; END")).build();

        SchemaDiffResult r = engine.diff(head, live);

        ChangeItem fn = only(r, ChangeType.MODIFIED, ObjectType.FUNCTION);
        assertTrue(fn.getDetails().contains("integer -> trigger"), fn.getDetails());
        ChangeItem tr = only(r, ChangeType.MODIFIED, ObjectType.TRIGGER);
        assertEquals(ObjectType.TABLE, tr.getRootType());
        assertEquals("public.orders", tr.getRootName());
        assertOrdered(r.getUpSql(), "DROP TRIGGER IF EXISTS orders_touch ON public.orders;",
                "DROP FUNCTION public.touch();", "CREATE OR REPLACE FUNCTION public.touch()",
                "CREATE TRIGGER orders_touch BEFORE UPDATE ON public.orders FOR EACH ROW EXECUTE FUNCTION public.touch();");
        assertFalse(fn.getWarnings().isEmpty());
    }

    @Test
    void overloadsAreSeparateObjects() {
        DatabaseSnapshot head = db("a").function(function(1, "total", "integer", "numeric", "BEGIN RETURN 1; END")).build();
        DatabaseSnapshot live = db("b")
                .function(function(1, "total", "integer", "numeric", "BEGIN RETURN 1; END"))
                .function(function(2, "total", "integer, text", "numeric", "BEGIN RETURN 2; END")).build();

        SchemaDiffResult r = engine.compare(head, live, true);

        ChangeItem added = only(r, ChangeType.ADDED, ObjectType.FUNCTION);
        assertEquals("public.total(integer, text)", added.getObjectName());
        assertTrue(r.getDownSql().contains("DROP FUNCTION public.total(integer, text);"), r.getDownSql());
    }

    @Test
    void functionWhoseSignatureUsesANewTableIsCreatedAfterIt() {
        DatabaseSnapshot head = db("a").build();
        DatabaseSnapshot live = db("b").table(table(10, "orders", col(1, "id", "integer")))
                .function(function(5, "open_orders", "", "SETOF orders", "BEGIN RETURN; END"))
                .function(function(6, "answer", "", "integer", "BEGIN RETURN 42; END")).build();

        String up = engine.compare(head, live, true).getUpSql();

        assertOrdered(up, "CREATE OR REPLACE FUNCTION public.answer()", "CREATE TABLE public.orders",
                "CREATE OR REPLACE FUNCTION public.open_orders()");
    }

    // ------------------------------------------------------------------ triggers

    @Test
    void triggerIsCreatedAfterItsFunctionAndPrintedWithExecuteFunction() {
        TableModel liveTable = table(10, "orders", col(1, "id", "integer"));
        liveTable.getTriggers().put("orders_touch", trigger(30, "orders_touch", "orders", "touch"));
        DatabaseSnapshot head = db("db1").table(table(10, "orders", col(1, "id", "integer"))).build();
        DatabaseSnapshot live = db("db1").table(liveTable)
                .function(function(5, "touch", "", "trigger", "BEGIN RETURN NEW; END")).build();

        SchemaDiffResult r = engine.diff(head, live);

        ChangeItem tr = only(r, ChangeType.ADDED, ObjectType.TRIGGER);
        assertEquals("public.orders.orders_touch", tr.getObjectName());
        assertOrdered(r.getUpSql(), "CREATE OR REPLACE FUNCTION public.touch()", "CREATE TRIGGER orders_touch");
        assertTrue(r.getUpSql().contains("EXECUTE FUNCTION public.touch();"), r.getUpSql());
        assertTrue(r.getDownSql().contains("DROP TRIGGER IF EXISTS orders_touch ON public.orders;"), r.getDownSql());
    }

    @Test
    void executeProcedureAndExecuteFunctionAreTheSameTrigger() {
        TableModel a = table(10, "orders", col(1, "id", "integer"));
        a.getTriggers().put("t1", trigger(30, "t1", "orders", "touch"));
        TableModel b = table(20, "orders", col(1, "id", "integer"));
        TriggerModel pg16 = trigger(40, "t1", "orders", "touch");
        pg16.setDefinition(pg16.getDefinition().replace("EXECUTE PROCEDURE", "EXECUTE FUNCTION"));
        b.getTriggers().put("t1", pg16);

        SchemaDiffResult r = engine.compare(db("pg11").table(a).build(), db("pg16").table(b).build(), true);

        assertFalse(r.isHasChanges(), r.getChanges().toString());
    }

    @Test
    void disabledTriggerIsOnlyAStateChange() {
        TableModel a = table(10, "orders", col(1, "id", "integer"));
        a.getTriggers().put("t1", trigger(30, "t1", "orders", "touch"));
        TableModel b = table(10, "orders", col(1, "id", "integer"));
        TriggerModel disabled = trigger(30, "t1", "orders", "touch");
        disabled.setEnabled("D");
        b.getTriggers().put("t1", disabled);

        SchemaDiffResult r = engine.diff(db("db1").table(a).build(), db("db1").table(b).build());

        ChangeItem item = only(r, ChangeType.MODIFIED, ObjectType.TRIGGER);
        assertEquals("ALTER TABLE public.orders DISABLE TRIGGER t1;", item.getUpSql());
        assertEquals("ALTER TABLE public.orders ENABLE TRIGGER t1;", item.getDownSql());
    }

    // ------------------------------------------------------------------ enum types

    @Test
    void addedEnumValueIsAddValueInItsOwnStep() {
        DatabaseSnapshot head = db("db1").enumType(enumType(7, "status", "new", "paid")).build();
        DatabaseSnapshot live = db("db1").enumType(enumType(7, "status", "new", "paid", "shipped"))
                .table(table(10, "orders", col(1, "id", "integer"))).build();

        SchemaDiffResult r = engine.diff(head, live);

        ChangeItem item = only(r, ChangeType.MODIFIED, ObjectType.TYPE);
        assertEquals("ALTER TYPE public.status ADD VALUE IF NOT EXISTS 'shipped' AFTER 'paid';", item.getUpSql());
        List<MigrationStep> steps = MigrationStep.plan(r.getUpSql());
        assertEquals(2, steps.size(), r.getUpSql());
        assertFalse(steps.get(0).transactional());
        assertTrue(steps.get(1).transactional());
        assertTrue(steps.get(1).sql().contains("CREATE TABLE public.orders"));
        // a single transaction (dry-run, bundle) keeps it: PostgreSQL 12+ accepts ADD VALUE in a transaction
        assertTrue(TransactionalScript.of(r.getUpSql()).sql().contains("ADD VALUE IF NOT EXISTS 'shipped'"));
        // the reverse cannot be done automatically
        assertTrue(item.getDownSql().startsWith("-- MANUAL ACTION REQUIRED"), item.getDownSql());
    }

    @Test
    void valuesAddedInFrontAndInTheMiddleKeepTheirPosition() {
        DatabaseSnapshot head = db("db1").enumType(enumType(7, "status", "b", "d")).build();
        DatabaseSnapshot live = db("db1").enumType(enumType(7, "status", "a0", "a", "b", "c", "d")).build();

        String up = engine.diff(head, live).getUpSql();

        assertOrdered(up, "ADD VALUE IF NOT EXISTS 'a0' BEFORE 'b';", "ADD VALUE IF NOT EXISTS 'a' AFTER 'a0';",
                "ADD VALUE IF NOT EXISTS 'c' AFTER 'b';");
    }

    @Test
    void removedOrReorderedEnumValuesNeedManualAction() {
        DatabaseSnapshot head = db("db1").enumType(enumType(7, "status", "new", "paid", "shipped")).build();
        DatabaseSnapshot live = db("db1").enumType(enumType(7, "status", "paid", "new")).build();

        SchemaDiffResult r = engine.diff(head, live);

        ChangeItem item = only(r, ChangeType.MODIFIED, ObjectType.TYPE);
        assertTrue(item.getUpSql().startsWith("-- MANUAL ACTION REQUIRED"), item.getUpSql());
        assertFalse(item.getWarnings().isEmpty());
    }

    @Test
    void newEnumTypeIsCreatedBeforeTablesAndDroppedAfterThem() {
        TableModel orders = table(10, "orders", col(1, "id", "integer"), col(2, "state", "status"));
        DatabaseSnapshot head = db("a").build();
        DatabaseSnapshot live = db("b").enumType(enumType(7, "status", "new", "it's done")).table(orders).build();

        SchemaDiffResult r = engine.compare(head, live, true);

        assertOrdered(r.getUpSql(), "CREATE TYPE public.status AS ENUM ('new', 'it''s done');", "CREATE TABLE public.orders");
        assertOrdered(r.getDownSql(), "DROP TABLE public.orders;", "DROP TYPE public.status;");
    }

    // ------------------------------------------------------------------ extensions

    @Test
    void extensionsAreCreatedFirstAndTheirVersionIsIgnored() {
        DatabaseSnapshot head = db("a").extension("citext", "public", "1.5").build();
        DatabaseSnapshot live = db("b").extension("citext", "public", "1.6").extension("pgcrypto", "public", "1.3")
                .extension("hstore", "ext", "1.8")
                .table(table(10, "users", col(1, "email", "citext"))).build();

        SchemaDiffResult r = engine.compare(head, live, true);

        // v1.9.1: citext is installed on both sides - another version is not a change, in either direction
        assertTrue(r.getChanges().stream().noneMatch(c -> c.getObjectName().equals("citext")), r.getChanges().toString());
        assertFalse(r.getUpSql().contains("UPDATE TO"), r.getUpSql());
        assertFalse(r.getDownSql().contains("UPDATE TO"), r.getDownSql());
        assertOrdered(r.getUpSql(), "CREATE EXTENSION IF NOT EXISTS \"pgcrypto\";", "CREATE TABLE public.users");
        // an extension outside public keeps its schema
        assertTrue(r.getUpSql().contains("CREATE EXTENSION IF NOT EXISTS \"hstore\" WITH SCHEMA ext;"), r.getUpSql());
        assertTrue(r.getDownSql().contains("DROP EXTENSION IF EXISTS \"pgcrypto\";"), r.getDownSql());

        // the reverse comparison (what the DOWN of a rollback is built from) does not downgrade either
        SchemaDiffResult back = engine.compare(live, head, true);
        assertFalse(back.getUpSql().contains("UPDATE TO"), back.getUpSql());
        assertTrue(engine.compare(db("a").extension("citext", "public", "1.5").build(),
                db("b").extension("citext", "public", "1.6").build(), true).getChanges().isEmpty());
    }

    // ------------------------------------------------------------------ comments

    @Test
    void commentsOnTablesAndColumns() {
        TableModel a = table(10, "orders", col(1, "id", "integer"), col(2, "total", "numeric"));
        TableModel b = table(10, "orders", col(1, "id", "integer"), col(2, "total", "numeric"));
        b.setComment("Customer orders");
        b.getColumns().get("total").setComment("Sum with VAT, it's gross");

        SchemaDiffResult r = engine.diff(db("db1").table(a).build(), db("db1").table(b).build());

        assertEquals(2, r.getChanges().size(), r.getChanges().toString());
        assertTrue(r.getChanges().stream().allMatch(c -> c.getObjectType() == ObjectType.COMMENT
                && c.getChangeType() == ChangeType.MODIFIED && "public.orders".equals(c.getRootName())));
        assertTrue(r.getUpSql().contains("COMMENT ON TABLE public.orders IS 'Customer orders';"), r.getUpSql());
        assertTrue(r.getUpSql().contains("COMMENT ON COLUMN public.orders.total IS 'Sum with VAT, it''s gross';"));
        assertTrue(r.getDownSql().contains("COMMENT ON TABLE public.orders IS NULL;"), r.getDownSql());

        SchemaDiffResult ignored = engine.diff(db("db1").table(a).build(), db("db1").table(b).build(),
                new DiffIgnoreRules(true, false, false, false, List.of()));
        assertFalse(ignored.isHasChanges());
    }

    @Test
    void commentsOfNewObjectsAreCreatedWithThem() {
        TableModel t = table(10, "orders", col(1, "id", "integer"));
        t.setComment("Orders");
        t.getColumns().get("id").setComment("Key");

        String up = engine.compare(db("a").build(), db("b").table(t).build(), true).getUpSql();

        assertOrdered(up, "CREATE TABLE public.orders", "COMMENT ON TABLE public.orders IS 'Orders';",
                "COMMENT ON COLUMN public.orders.id IS 'Key';");
    }

    // ------------------------------------------------------------------ owners, grants, tablespaces

    @Test
    void ownersGrantsAndTablespacesAreComparedWhenNotIgnored() {
        TableModel a = table(10, "orders", col(1, "id", "integer"));
        a.setOwner("app");
        a.setAcl(List.of("reporting=SELECT", "\"Ops Team\"=UPDATE*"));
        a.setTablespace("pg_default");
        TableModel b = table(10, "orders", col(1, "id", "integer"));
        b.setOwner("app_owner");
        b.setAcl(List.of("reporting=SELECT", "reporting=INSERT", "\"Ops Team\"=UPDATE"));
        b.setTablespace("fast_ssd");

        SchemaDiffResult r = engine.diff(db("db1").table(a).build(), db("db1").table(b).build());

        ChangeItem item = only(r, ChangeType.MODIFIED, ObjectType.TABLE);
        String up = item.getUpSql();
        assertTrue(up.contains("ALTER TABLE public.orders OWNER TO app_owner;"), up);
        assertTrue(up.contains("REVOKE GRANT OPTION FOR UPDATE ON TABLE public.orders FROM \"Ops Team\";"), up);
        assertTrue(up.contains("GRANT INSERT ON TABLE public.orders TO reporting;"), up);
        assertTrue(up.contains("ALTER TABLE public.orders SET TABLESPACE fast_ssd;"), up);
        String down = item.getDownSql();
        assertTrue(down.contains("REVOKE INSERT ON TABLE public.orders FROM reporting;"), down);
        assertTrue(down.contains("GRANT UPDATE ON TABLE public.orders TO \"Ops Team\" WITH GRANT OPTION;"), down);

        // the defaults ignore owners, grants and tablespaces
        assertFalse(new SchemaDiffEngine().diff(db("db1").table(a).build(), db("db1").table(b).build()).isHasChanges());
    }

    @Test
    void newFunctionRevokesTheDefaultPublicExecuteWhenTheTargetHasNone() {
        FunctionModel f = function(5, "secret", "", "integer", "BEGIN RETURN 1; END");
        f.setAcl(List.of("app=EXECUTE"));

        String up = engine.compare(db("a").build(), db("b").function(f).build(), true).getUpSql();

        assertOrdered(up, "CREATE OR REPLACE FUNCTION public.secret()",
                "REVOKE EXECUTE ON FUNCTION public.secret() FROM PUBLIC;", "GRANT EXECUTE ON FUNCTION public.secret() TO app;");
    }

    // ------------------------------------------------------------------ ignore rules, legacy snapshots

    @Test
    void excludedPatternsAndPartitionsAreLeftOut() {
        TableModel parent = TableModel.builder().oid(1).schemaName("public").name("events").partitioned(true)
                .partitionKeyDef("RANGE (at)").columns(new LinkedHashMap<>(Map.of("at", col(1, "at", "date")))).build();
        TableModel p1 = TableModel.builder().oid(2).schemaName("public").name("events_2026_01")
                .parentTable("public.events").partitionBoundExpr("FOR VALUES FROM ('2026-01-01') TO ('2026-02-01')").build();
        DatabaseSnapshot current = db("a").table(parent).build();
        DatabaseSnapshot target = db("b").table(parent).table(p1).table(table(3, "tmp_import", col(1, "x", "text")))
                .table(table(4, "bak_orders", col(1, "x", "text")))
                .function(function(9, "tmp_fix", "", "void", "BEGIN END")).build();

        DiffIgnoreRules rules = new DiffIgnoreRules(false, true, true, true, List.of("tmp_%", "re:^bak_"));
        SchemaDiffResult r = engine.compare(current, target, true, rules);

        assertFalse(r.isHasChanges(), r.getChanges().toString());
        assertTrue(engine.compare(current, target, true, DiffIgnoreRules.NONE).getChanges().size() >= 4);
    }

    @Test
    void legacySnapshotsSkipTheNewCategories() {
        TableModel old = table(10, "orders", col(1, "id", "integer"));
        DatabaseSnapshot head = db("db1").table(old).build();
        head.setFormatVersion(1);
        TableModel now = table(10, "orders", col(1, "id", "integer"));
        now.setComment("Orders");
        now.getTriggers().put("t1", trigger(30, "t1", "orders", "touch"));
        DatabaseSnapshot live = db("db1").table(now).extension("citext", "public", "1.6")
                .function(function(5, "touch", "", "trigger", "BEGIN RETURN NEW; END"))
                .enumType(enumType(7, "status", "a")).build();

        assertFalse(engine.diff(head, live).isHasChanges(), engine.diff(head, live).getChanges().toString());
    }

    @Test
    void rootsGroupSubObjectsUnderTheirTable() {
        TableModel a = table(10, "orders", col(1, "id", "integer"));
        TableModel b = table(10, "orders", col(1, "id", "bigint"), col(2, "note", "text"));

        SchemaDiffResult r = engine.diff(db("db1").table(a).build(), db("db1").table(b).build());

        assertTrue(r.getChanges().stream().allMatch(c -> c.getRootType() == ObjectType.TABLE
                && "public.orders".equals(c.getRootName())), r.getChanges().toString());
    }
}
