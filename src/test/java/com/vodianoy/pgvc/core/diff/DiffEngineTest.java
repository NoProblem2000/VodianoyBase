package com.vodianoy.pgvc.core.diff;

import com.vodianoy.pgvc.core.model.ColumnDef;
import com.vodianoy.pgvc.core.model.ConstraintDef;
import com.vodianoy.pgvc.core.model.DatabaseSnapshot;
import com.vodianoy.pgvc.core.model.TableDef;
import com.vodianoy.pgvc.core.model.ViewDef;
import com.vodianoy.pgvc.core.model.ViewDependency;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiffEngineTest {

    private final DiffEngine engine = new DiffEngine();

    private static ColumnDef col(int attnum, String name, String type) {
        return new ColumnDef(attnum, name, type, false, null, null, true);
    }

    private static TableDef table(long oid, String name, List<ColumnDef> cols, List<ConstraintDef> cons) {
        return new TableDef(oid, "public", name, false, null, null, null, null, cols, cons);
    }

    private static ViewDef view(long oid, String name, String def, String sig, ViewDependency... deps) {
        return new ViewDef(oid, "public", name, false, def, sig, List.of(deps));
    }

    private static DatabaseSnapshot snap(String db, List<TableDef> tables, List<ViewDef> views) {
        return new DatabaseSnapshot(1, db, 160000, List.of("public"), tables, List.of(), List.of(), views);
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

    @Test
    void columnTypeChangeRecreatesDependentViewsInSafeOrder() {
        TableDef before = table(10, "t", List.of(col(1, "id", "integer"), col(2, "a", "integer")), List.of());
        TableDef after = table(10, "t", List.of(col(1, "id", "integer"), col(2, "a", "bigint")), List.of());
        ViewDef v = view(20, "v", " SELECT id, a FROM t;", "s1", new ViewDependency("public", "t", "a"));
        ViewDef w = view(21, "w", " SELECT a FROM v;", "s2", new ViewDependency("public", "v", "a"));

        DiffResult r = engine.diff(snap("db1", List.of(before), List.of(v, w)), snap("db1", List.of(after), List.of(v, w)));

        assertOrdered(r.sql(),
                "DROP VIEW public.w;",
                "DROP VIEW public.v;",
                "ALTER TABLE public.t ALTER COLUMN a TYPE bigint USING a::bigint;",
                "CREATE VIEW public.v AS",
                "CREATE VIEW public.w AS");
    }

    @Test
    void viewOnUnchangedColumnIsKept() {
        TableDef before = table(10, "t", List.of(col(1, "id", "integer"), col(2, "a", "integer")), List.of());
        TableDef after = table(10, "t", List.of(col(1, "id", "integer"), col(2, "a", "bigint")), List.of());
        ViewDef v = view(20, "v", " SELECT id FROM t;", "s1", new ViewDependency("public", "t", "id"));

        DiffResult r = engine.diff(snap("db1", List.of(before), List.of(v)), snap("db1", List.of(after), List.of(v)));

        assertFalse(r.sql().contains("DROP VIEW"), r.sql());
    }

    @Test
    void renamesAreDetectedByOidAndAttnum() {
        TableDef before = table(10, "users", List.of(col(1, "id", "integer"), col(2, "email", "text")), List.of());
        TableDef after = table(10, "customers", List.of(col(1, "id", "integer"), col(2, "email_address", "text")), List.of());

        DiffResult r = engine.diff(snap("db1", List.of(before), List.of()), snap("db1", List.of(after), List.of()));

        assertOrdered(r.sql(),
                "ALTER TABLE public.users RENAME TO customers;",
                "ALTER TABLE public.customers RENAME COLUMN email TO email_address;");
        assertFalse(r.sql().contains("DROP"), r.sql());
    }

    @Test
    void oidsAreNotComparedAcrossDatabases() {
        TableDef before = table(10, "users", List.of(col(1, "id", "integer")), List.of());
        TableDef after = table(10, "customers", List.of(col(1, "id", "integer")), List.of());

        DiffResult r = engine.diff(snap("db1", List.of(before), List.of()), snap("db2", List.of(after), List.of()));

        assertTrue(r.sql().contains("DROP TABLE public.users;"), r.sql());
        assertTrue(r.sql().contains("CREATE TABLE public.customers"), r.sql());
    }

    @Test
    void partitionParentIsCreatedBeforePartitions() {
        TableDef parent = new TableDef(1, "public", "events", true, "RANGE (ts)", null, null, null,
                List.of(col(1, "ts", "date")), List.of());
        TableDef child = new TableDef(2, "public", "events_2026", false, null, "public", "events",
                "FOR VALUES FROM ('2026-01-01') TO ('2027-01-01')", List.of(col(1, "ts", "date")), List.of());

        DiffResult r = engine.diff(DatabaseSnapshot.empty(), snap("db1", List.of(child, parent), List.of()));

        assertOrdered(r.sql(),
                "CREATE TABLE public.events (",
                "PARTITION BY RANGE (ts);",
                "CREATE TABLE public.events_2026 PARTITION OF public.events\n    FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');");
    }

    @Test
    void changedPartitionBoundIsDetachedAndReattached() {
        TableDef parent = new TableDef(1, "public", "events", true, "RANGE (ts)", null, null, null,
                List.of(col(1, "ts", "date")), List.of());
        TableDef c1 = new TableDef(2, "public", "p1", false, null, "public", "events",
                "FOR VALUES FROM ('2026-01-01') TO ('2026-02-01')", List.of(col(1, "ts", "date")), List.of());
        TableDef c2 = new TableDef(2, "public", "p1", false, null, "public", "events",
                "FOR VALUES FROM ('2026-01-01') TO ('2026-03-01')", List.of(col(1, "ts", "date")), List.of());

        DiffResult r = engine.diff(snap("db1", List.of(parent, c1), List.of()), snap("db1", List.of(parent, c2), List.of()));

        assertOrdered(r.sql(),
                "ALTER TABLE public.events DETACH PARTITION public.p1;",
                "ALTER TABLE public.events ATTACH PARTITION public.p1\n    FOR VALUES FROM ('2026-01-01') TO ('2026-03-01');");
    }

    @Test
    void foreignKeysAreDroppedBeforeTables() {
        ConstraintDef fk = new ConstraintDef(30, "b_a_fk", "f", "FOREIGN KEY (a_id) REFERENCES a(id)", "x");
        TableDef a = table(1, "a", List.of(col(1, "id", "integer")), List.of());
        TableDef b = table(2, "b", List.of(col(1, "a_id", "integer")), List.of(fk));

        DiffResult r = engine.diff(snap("db1", List.of(a, b), List.of()), snap("db1", List.of(), List.of()));

        assertOrdered(r.sql(), "ALTER TABLE public.b DROP CONSTRAINT b_a_fk;", "DROP TABLE public.");
    }

    @Test
    void identicalSnapshotsProduceNoChanges() {
        TableDef t = table(10, "t", List.of(col(1, "id", "integer")),
                List.of(new ConstraintDef(11, "t_pkey", "p", "PRIMARY KEY (id)", "sig")));
        ViewDef v = view(20, "v", " SELECT id FROM t;", "s1", new ViewDependency("public", "t", "id"));

        DiffResult r = engine.diff(snap("db1", List.of(t), List.of(v)), snap("db1", List.of(t), List.of(v)));

        assertFalse(r.hasChanges(), r.sql());
    }

    @Test
    void identifiersAreQuotedWhenNeeded() {
        TableDef t = new TableDef(1, "public", "Mixed Case", false, null, null, null, null,
                List.of(col(1, "order", "text"), col(2, "normal_col", "integer")), List.of());

        DiffResult r = engine.diff(DatabaseSnapshot.empty(), snap("db1", List.of(t), List.of()));

        assertTrue(r.sql().contains("CREATE TABLE public.\"Mixed Case\" ("), r.sql());
        assertTrue(r.sql().contains("\"order\" text"), r.sql());
        assertTrue(r.sql().contains("normal_col integer"), r.sql());
    }
}
