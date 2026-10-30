package com.vodianoy.pgvc.diff;

import com.vodianoy.pgvc.model.schema.ColumnModel;
import com.vodianoy.pgvc.model.schema.ConstraintModel;
import com.vodianoy.pgvc.model.schema.ConstraintType;
import com.vodianoy.pgvc.model.schema.DatabaseSnapshot;
import com.vodianoy.pgvc.model.schema.IndexModel;
import com.vodianoy.pgvc.model.schema.TableModel;
import com.vodianoy.pgvc.model.schema.ViewDependency;
import com.vodianoy.pgvc.model.schema.ViewModel;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaDiffEngineTest {

    private final SchemaDiffEngine engine = new SchemaDiffEngine();

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

    private static ViewModel view(long oid, String name, String definition, String signature, ViewDependency... deps) {
        return ViewModel.builder().oid(oid).schemaName("public").name(name).definition(definition).signature(signature)
                .populated(true).dependencies(List.of(deps)).build();
    }

    private static ViewDependency dep(String relation, String column) {
        return ViewDependency.builder().relation(relation).relationKind("r").column(column).build();
    }

    private static DatabaseSnapshot snapshot(String db, List<TableModel> tables, List<ViewModel> views) {
        Map<String, TableModel> t = new LinkedHashMap<>();
        tables.forEach(x -> t.put(x.getQualifiedName(), x));
        Map<String, ViewModel> v = new LinkedHashMap<>();
        views.forEach(x -> v.put(x.getQualifiedName(), x));
        return DatabaseSnapshot.builder().sourceDatabaseId(db).serverVersion(160000).schemas(List.of("public"))
                .tables(t).views(v).build();
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

    // ------------------------------------------------------------------ tests

    @Test
    void tableRenameIsDetectedByOid() {
        DatabaseSnapshot head = snapshot("db1", List.of(table(10, "users", col(1, "id", "integer"))), List.of());
        DatabaseSnapshot live = snapshot("db1", List.of(table(10, "customers", col(1, "id", "integer"))), List.of());

        SchemaDiffResult r = engine.diff(head, live);

        ChangeItem rename = only(r, ChangeType.RENAMED, ObjectType.TABLE);
        assertEquals("public.customers", rename.getObjectName());
        assertEquals("ALTER TABLE public.users RENAME TO customers;", rename.getUpSql());
        assertEquals("ALTER TABLE public.customers RENAME TO users;", rename.getDownSql());
        assertEquals(1, r.getChanges().size());
        assertFalse(r.getUpSql().contains("DROP"), r.getUpSql());
    }

    @Test
    void columnRenameIsDetectedByAttnumAndColumnsOfRenamedTableAreCompared() {
        DatabaseSnapshot head = snapshot("db1", List.of(table(10, "users", col(1, "id", "integer"),
                col(2, "email", "text"))), List.of());
        DatabaseSnapshot live = snapshot("db1", List.of(table(10, "customers", col(1, "id", "bigint"),
                col(2, "email_address", "text"))), List.of());

        SchemaDiffResult r = engine.diff(head, live);

        ChangeItem rename = only(r, ChangeType.RENAMED, ObjectType.COLUMN);
        assertEquals("ALTER TABLE public.customers RENAME COLUMN email TO email_address;", rename.getUpSql());
        assertEquals("ALTER TABLE public.users RENAME COLUMN email_address TO email;", rename.getDownSql());
        ChangeItem type = only(r, ChangeType.MODIFIED, ObjectType.COLUMN);
        assertEquals("public.customers.id", type.getObjectName());
        assertOrdered(r.getUpSql(),
                "ALTER TABLE public.users RENAME TO customers;",
                "ALTER TABLE public.customers RENAME COLUMN email TO email_address;",
                "ALTER TABLE public.customers ALTER COLUMN id TYPE bigint USING id::bigint;");
    }

    @Test
    void identitiesAreNotComparedAcrossDatabases() {
        DatabaseSnapshot head = snapshot("db1", List.of(table(10, "users", col(1, "id", "integer"))), List.of());
        DatabaseSnapshot live = snapshot("db2", List.of(table(10, "customers", col(1, "id", "integer"))), List.of());

        SchemaDiffResult r = engine.diff(head, live);

        only(r, ChangeType.DELETED, ObjectType.TABLE);
        only(r, ChangeType.ADDED, ObjectType.TABLE);
    }

    @Test
    void partitionedTableAndPartitions() {
        TableModel parent = table(1, "events", col(1, "id", "bigint"), col(2, "ts", "date")).toBuilder()
                .partitioned(true).partitionKeyDef("RANGE (ts)").build();
        TableModel child = table(2, "events_2026", col(1, "id", "bigint"), col(2, "ts", "date")).toBuilder()
                .parentTable("public.events").partitionBoundExpr("FOR VALUES FROM ('2026-01-01') TO ('2027-01-01')").build();
        IndexModel idx = IndexModel.builder().oid(3).name("events_ts_idx")
                .definition("CREATE INDEX events_ts_idx ON ONLY public.events USING btree (ts)").build();
        parent.getIndexes().put(idx.getName(), idx);

        SchemaDiffResult r = engine.diff(DatabaseSnapshot.builder().build(), snapshot("db1", List.of(child, parent), List.of()));

        assertEquals("public.events", only(r, ChangeType.ADDED, ObjectType.TABLE).getObjectName());
        ChangeItem partition = only(r, ChangeType.ADDED, ObjectType.PARTITION);
        assertEquals("CREATE TABLE public.events_2026 PARTITION OF public.events\n"
                + "    FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');", partition.getUpSql());
        assertEquals("-- public.events_2026 is dropped together with its parent public.events", partition.getDownSql());
        assertOrdered(r.getUpSql(),
                "CREATE TABLE public.events (",
                "    PARTITION BY RANGE (ts);",
                "CREATE TABLE public.events_2026 PARTITION OF public.events",
                "CREATE INDEX events_ts_idx ON public.events USING btree (ts);");
        assertFalse(r.getUpSql().contains("ON ONLY"), "partitioned index must cascade to partitions");
    }

    @Test
    void changedPartitionBoundIsDetachedAndReattached() {
        TableModel parent = table(1, "events", col(1, "ts", "date")).toBuilder().partitioned(true)
                .partitionKeyDef("RANGE (ts)").build();
        TableModel before = table(2, "p1", col(1, "ts", "date")).toBuilder().parentTable("public.events")
                .partitionBoundExpr("FOR VALUES FROM ('2026-01-01') TO ('2026-02-01')").build();
        TableModel after = before.toBuilder().partitionBoundExpr("FOR VALUES FROM ('2026-01-01') TO ('2026-03-01')").build();

        SchemaDiffResult r = engine.diff(snapshot("db1", List.of(parent, before), List.of()),
                snapshot("db1", List.of(parent, after), List.of()));

        ChangeItem item = only(r, ChangeType.MODIFIED, ObjectType.PARTITION);
        assertOrdered(item.getUpSql(),
                "ALTER TABLE public.events DETACH PARTITION public.p1;",
                "ALTER TABLE public.events ATTACH PARTITION public.p1\n    FOR VALUES FROM ('2026-01-01') TO ('2026-03-01');");
    }

    @Test
    void viewsReadingAChangedColumnAreDroppedFirstAndRecreatedLast() {
        TableModel before = table(10, "t", col(1, "id", "integer"), col(2, "a", "integer"));
        TableModel after = table(10, "t", col(1, "id", "integer"), col(2, "a", "bigint"));
        ViewModel v = view(20, "v", " SELECT id, a FROM t;", "s1", dep("public.t", "a"));
        ViewModel w = view(21, "w", " SELECT a FROM v;", "s2", dep("public.v", "a"));

        SchemaDiffResult r = engine.diff(snapshot("db1", List.of(before), List.of(v, w)),
                snapshot("db1", List.of(after), List.of(v, w)));

        assertOrdered(r.getUpSql(),
                "DROP VIEW IF EXISTS public.w;",
                "DROP VIEW IF EXISTS public.v;",
                "ALTER TABLE public.t ALTER COLUMN a TYPE bigint USING a::bigint;",
                "CREATE OR REPLACE VIEW public.v AS",
                "CREATE OR REPLACE VIEW public.w AS");
        assertOrdered(r.getDownSql(),
                "DROP VIEW IF EXISTS public.w;",
                "DROP VIEW IF EXISTS public.v;",
                "ALTER TABLE public.t ALTER COLUMN a TYPE integer USING a::integer;",
                "CREATE OR REPLACE VIEW public.v AS",
                "CREATE OR REPLACE VIEW public.w AS");
        assertEquals(2L, r.getChanges().stream().filter(c -> c.getObjectType() == ObjectType.VIEW).count());
    }

    @Test
    void viewsAreNotRecreatedForChangesTheyDoNotRead() {
        TableModel before = table(10, "t", col(1, "id", "integer"), col(2, "a", "integer"));
        TableModel after = table(10, "t", col(1, "id", "integer"), col(2, "a", "bigint"), col(3, "b", "text"));
        ViewModel v = view(20, "v", " SELECT id FROM t;", "s1", dep("public.t", "id"));

        SchemaDiffResult r = engine.diff(snapshot("db1", List.of(before), List.of(v)),
                snapshot("db1", List.of(after), List.of(v)));

        assertFalse(r.getUpSql().contains("VIEW"), r.getUpSql());
    }

    @Test
    void foreignKeysAreDroppedBeforeTables() {
        ConstraintModel fk = ConstraintModel.builder().oid(30).name("b_a_fk").type(ConstraintType.FOREIGN_KEY)
                .definition("FOREIGN KEY (a_id) REFERENCES public.a(id)").signature("x").build();
        TableModel a = table(1, "a", col(1, "id", "integer"));
        TableModel b = table(2, "b", col(1, "a_id", "integer"));
        b.getConstraints().put(fk.getName(), fk);

        SchemaDiffResult r = engine.diff(snapshot("db1", List.of(a, b), List.of()), snapshot("db1", List.of(), List.of()));

        assertOrdered(r.getUpSql(), "ALTER TABLE public.b DROP CONSTRAINT b_a_fk;", "DROP TABLE public.");
        assertOrdered(r.getDownSql(), "CREATE TABLE public.a", "CREATE TABLE public.b",
                "ALTER TABLE public.b ADD CONSTRAINT b_a_fk FOREIGN KEY (a_id) REFERENCES public.a(id);");
        assertTrue(r.getWarnings().stream().anyMatch(w -> w.contains("data will be lost")));
    }

    @Test
    void identicalSnapshotsHaveNoChanges() {
        TableModel t = table(10, "t", col(1, "id", "integer"));
        ViewModel v = view(20, "v", " SELECT id FROM t;", "s1", dep("public.t", "id"));

        SchemaDiffResult r = engine.diff(snapshot("db1", List.of(t), List.of(v)), snapshot("db1", List.of(t), List.of(v)));

        assertFalse(r.isHasChanges());
        assertTrue(r.getChanges().isEmpty());
        assertEquals("-- No schema changes\n", r.getUpSql());
    }

    @Test
    void identifiersAreQuotedWhenNeeded() {
        TableModel t = TableModel.builder().oid(1).schemaName("public").name("Mixed Case")
                .columns(new LinkedHashMap<>(Map.of("order", col(1, "order", "text")))).build();

        SchemaDiffResult r = engine.diff(DatabaseSnapshot.builder().build(), snapshot("db1", List.of(t), List.of()));

        assertTrue(r.getUpSql().contains("CREATE TABLE public.\"Mixed Case\" (\n    \"order\" text\n);"), r.getUpSql());
    }

    @Test
    void everyChangeCarriesItsOwnUpAndDownSql() {
        DatabaseSnapshot head = snapshot("db1", List.of(table(10, "users", col(1, "id", "integer"),
                col(2, "legacy", "text"))), List.of());
        DatabaseSnapshot live = snapshot("db1", List.of(table(10, "users", col(1, "id", "integer"),
                col(3, "created", "date")), table(11, "orders", col(1, "id", "integer"))), List.of());

        SchemaDiffResult r = engine.diff(head, live);

        assertEquals(3, r.getChanges().size(), r.getChanges().toString());
        for (ChangeItem c : r.getChanges()) {
            assertFalse(c.getUpSql().isBlank(), "up of " + c);
            assertFalse(c.getDownSql().isBlank(), "down of " + c);
        }
        assertEquals("ALTER TABLE public.users ADD COLUMN legacy text;",
                only(r, ChangeType.DELETED, ObjectType.COLUMN).getDownSql());
    }

    // ------------------------------------------------------------------ cross-database compare (Step 9)

    @Test
    void compareByNameIgnoresOidsAndAttnumsOfAnotherDatabase() {
        // same structure, completely different identities (another server)
        DatabaseSnapshot customer = snapshot("customer", List.of(table(500, "users", col(7, "id", "integer"),
                col(8, "email", "text"))), List.of());
        DatabaseSnapshot reference = snapshot("dev", List.of(table(10, "users", col(1, "id", "integer"),
                col(3, "email", "text"))), List.of());
        SchemaDiffResult r = engine.compare(customer, reference, true);
        assertFalse(r.isHasChanges(), r.getUpSql());
        assertTrue(r.getUpStatements().isEmpty());
    }

    @Test
    void compareByNameNeverReportsRenamesEvenWithEqualIdentities() {
        // a plain diff of the same database sees a rename (same oid); a name-only compare must not
        DatabaseSnapshot current = snapshot("db1", List.of(table(10, "clients", col(1, "id", "integer"))), List.of());
        DatabaseSnapshot target = snapshot("db1", List.of(table(10, "customers", col(1, "id", "integer"))), List.of());
        only(engine.diff(current, target), ChangeType.RENAMED, ObjectType.TABLE);

        SchemaDiffResult r = engine.compare(current, target, true);
        ChangeItem added = only(r, ChangeType.ADDED, ObjectType.TABLE);
        ChangeItem extra = only(r, ChangeType.DELETED, ObjectType.TABLE);
        assertEquals("public.customers", added.getObjectName());
        assertFalse(added.isExtraInTargetDb());
        assertEquals("public.clients", extra.getObjectName());
        assertTrue(extra.isExtraInTargetDb());
        // every change has an id = its position
        for (int i = 0; i < r.getChanges().size(); i++) {
            assertEquals(i, r.getChanges().get(i).getId());
        }
    }

    @Test
    void selectedChangesAreAssembledInDependencySafeOrder() {
        DatabaseSnapshot current = snapshot("customer", List.of(
                table(500, "users", col(1, "id", "integer"), col(2, "legacy_code", "text"))), List.of());
        DatabaseSnapshot target = snapshot("dev", List.of(
                table(10, "users", col(1, "id", "integer"), col(2, "email", "text")),
                table(11, "orders", col(1, "id", "integer"), col(2, "user_id", "integer"))), List.of());
        SchemaDiffResult r = engine.compare(current, target, true);

        ChangeItem email = r.getChanges().stream().filter(c -> c.getObjectName().equals("public.users.email"))
                .findFirst().orElseThrow();
        ChangeItem orders = r.getChanges().stream().filter(c -> c.getObjectName().equals("public.orders"))
                .findFirst().orElseThrow();
        ChangeItem legacy = r.getChanges().stream().filter(c -> c.getObjectName().equals("public.users.legacy_code"))
                .findFirst().orElseThrow();
        assertEquals(ChangeType.ADDED, email.getChangeType());
        assertTrue(legacy.isExtraInTargetDb());

        // every statement belongs to a known change
        for (ScriptStatement st : r.getUpStatements()) {
            assertTrue(st.changeId() >= 0 && st.changeId() < r.getChanges().size(), st.toString());
        }
        String safe = SchemaDiffEngine.assemble(r.getUpStatements(), List.of(email.getId(), orders.getId()));
        assertTrue(safe.contains("ADD COLUMN email"), safe);
        assertTrue(safe.contains("CREATE TABLE public.orders"), safe);
        assertFalse(safe.contains("legacy_code"), safe);
        String down = SchemaDiffEngine.assemble(r.getDownStatements(), List.of(email.getId(), orders.getId()));
        assertTrue(down.contains("DROP COLUMN email"), down);
        assertTrue(down.contains("DROP TABLE public.orders"), down);
        assertFalse(down.contains("legacy_code"), down);

        String all = SchemaDiffEngine.assemble(r.getUpStatements(), List.of(email.getId(), orders.getId(), legacy.getId()));
        assertTrue(all.contains("DROP COLUMN legacy_code"), all);
        assertEquals("", SchemaDiffEngine.assemble(r.getUpStatements(), List.of()));
        // the plain diff does not carry statements
        assertTrue(engine.diff(current, target).getUpStatements().isEmpty());
    }
}
