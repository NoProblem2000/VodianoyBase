package com.vodianoy.pgvc.diff;

import com.vodianoy.pgvc.model.schema.ColumnModel;
import com.vodianoy.pgvc.model.schema.ConstraintModel;
import com.vodianoy.pgvc.model.schema.ConstraintType;
import com.vodianoy.pgvc.model.schema.DatabaseSnapshot;
import com.vodianoy.pgvc.model.schema.ReferenceDataModel;
import com.vodianoy.pgvc.model.schema.TableModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rows of reference-data tables: INSERT ... ON CONFLICT / DELETE by primary key. */
class ReferenceDataDiffTest {

    private final SchemaDiffEngine engine = new SchemaDiffEngine(() -> DiffIgnoreRules.NONE);

    private static List<String> row(String... values) {
        return new ArrayList<>(Arrays.asList(values));
    }

    @SafeVarargs
    private static ReferenceDataModel data(List<String> columns, List<String> types, List<String> pk, List<String>... rows) {
        List<List<String>> r = new ArrayList<>(List.of(rows));
        return ReferenceDataModel.builder().columns(new ArrayList<>(columns)).columnTypes(new ArrayList<>(types))
                .primaryKey(new ArrayList<>(pk)).rows(r).rowCount(r.size()).rowsHash(ReferenceDataModel.hash(r)).build();
    }

    private static TableModel statuses(ReferenceDataModel data) {
        Map<String, ColumnModel> cols = new LinkedHashMap<>();
        cols.put("id", ColumnModel.builder().attnum(1).name("id").dataType("integer").nullable(false).build());
        cols.put("code", ColumnModel.builder().attnum(2).name("code").dataType("text").nullable(false).build());
        cols.put("title", ColumnModel.builder().attnum(3).name("title").dataType("character varying(100)").nullable(true).build());
        Map<String, ConstraintModel> cons = new LinkedHashMap<>();
        cons.put("statuses_pkey", ConstraintModel.builder().oid(11).name("statuses_pkey").type(ConstraintType.PRIMARY_KEY)
                .definition("PRIMARY KEY (id)").columns(new ArrayList<>(List.of("id"))).validated(true).build());
        return TableModel.builder().oid(10).schemaName("ref").name("statuses").columns(cols).constraints(cons)
                .referenceData(data).build();
    }

    private static final List<String> COLS = List.of("id", "code", "title");
    private static final List<String> TYPES = List.of("integer", "text", "character varying(100)");

    private static DatabaseSnapshot db(String id, TableModel... tables) {
        DatabaseSnapshot s = DatabaseSnapshot.builder().sourceDatabaseId(id).serverVersion(160000).schemas(List.of("ref")).build();
        for (TableModel t : tables) {
            s.getTables().put(t.getQualifiedName(), t);
        }
        return s;
    }

    private static ChangeItem dataItem(SchemaDiffResult r) {
        List<ChangeItem> items = r.getChanges().stream().filter(c -> c.getObjectType() == ObjectType.DATA).toList();
        assertEquals(1, items.size(), r.getChanges().toString());
        return items.get(0);
    }

    @Test
    void changedAddedAndDeletedRowsBecomeUpsertsAndDeletes() {
        TableModel head = statuses(data(COLS, TYPES, List.of("id"),
                row("1", "new", "New"), row("2", "paid", "Paid"), row("3", "old", null)));
        TableModel live = statuses(data(COLS, TYPES, List.of("id"),
                row("1", "new", "New"), row("2", "paid", "Paid, it's done"), row("4", "shipped", "Shipped")));

        SchemaDiffResult r = engine.diff(db("db1", head), db("db1", live));

        ChangeItem item = dataItem(r);
        assertEquals(ChangeType.MODIFIED, item.getChangeType());
        assertEquals("ref.statuses", item.getObjectName());
        assertEquals(ObjectType.TABLE, item.getRootType());
        assertTrue(item.getDetails().contains("1 row(s) added") && item.getDetails().contains("1 row(s) changed")
                && item.getDetails().contains("1 row(s) deleted"), item.getDetails());
        String up = item.getUpSql();
        assertTrue(up.contains("DELETE FROM ref.statuses WHERE id = '3'::integer;"), up);
        assertTrue(up.contains("INSERT INTO ref.statuses (id, code, title) VALUES ('2'::integer, 'paid'::text,"
                + " 'Paid, it''s done'::character varying(100)) ON CONFLICT (id) DO UPDATE SET code = EXCLUDED.code,"
                + " title = EXCLUDED.title;"), up);
        assertTrue(up.contains("VALUES ('4'::integer, 'shipped'::text, 'Shipped'::character varying(100))"), up);
        assertTrue(up.indexOf("DELETE FROM") < up.indexOf("INSERT INTO"), up);
        // the reverse restores the old rows
        String down = item.getDownSql();
        assertTrue(down.contains("DELETE FROM ref.statuses WHERE id = '4'::integer;"), down);
        assertTrue(down.contains("VALUES ('3'::integer, 'old'::text, NULL)"), down);
        assertTrue(down.contains("'Paid'::character varying(100)"), down);
    }

    @Test
    void identicalRowsAreNoChange() {
        TableModel a = statuses(data(COLS, TYPES, List.of("id"), row("1", "new", "New")));
        TableModel b = statuses(data(COLS, TYPES, List.of("id"), row("1", "new", "New")));
        assertFalse(engine.diff(db("db1", a), db("db1", b)).isHasChanges());
    }

    @Test
    void newlyTrackedTableInsertsEverythingAndItsReverseDeletesNothing() {
        TableModel head = statuses(null);
        TableModel live = statuses(data(COLS, TYPES, List.of("id"), row("1", "new", "New"), row("2", "paid", "Paid")));

        SchemaDiffResult r = engine.diff(db("db1", head), db("db1", live));

        ChangeItem item = dataItem(r);
        assertTrue(item.getDetails().startsWith("now tracked as reference data: 2 row(s) added"), item.getDetails());
        assertFalse(item.getUpSql().contains("DELETE"), item.getUpSql());
        assertTrue(item.getDownSql().isBlank(), item.getDownSql());
        assertFalse(r.getDownSql().contains("DELETE"), r.getDownSql());
    }

    @Test
    void notTrackedOnTheTargetSideNeverDeletes() {
        TableModel head = statuses(data(COLS, TYPES, List.of("id"), row("1", "new", "New")));
        TableModel live = statuses(null);
        assertFalse(engine.diff(db("db1", head), db("db1", live)).isHasChanges());
    }

    @Test
    void newTableIsCreatedThenFilled() {
        TableModel t = statuses(data(COLS, TYPES, List.of("id"), row("1", "new", "New")));

        SchemaDiffResult r = engine.compare(db("a"), db("b", t), true);

        assertEquals(ChangeType.ADDED, dataItem(r).getChangeType());
        String up = r.getUpSql();
        assertTrue(up.indexOf("CREATE TABLE ref.statuses") < up.indexOf("INSERT INTO ref.statuses"), up);
        assertTrue(up.indexOf("ADD CONSTRAINT statuses_pkey") < up.indexOf("INSERT INTO ref.statuses"), up);
    }

    @Test
    void compositeKeysIdentityAlwaysAndKeyOnlyTables() {
        Map<String, ColumnModel> cols = new LinkedHashMap<>();
        cols.put("a", ColumnModel.builder().attnum(1).name("a").dataType("integer").identityGeneration("ALWAYS").build());
        cols.put("b", ColumnModel.builder().attnum(2).name("b").dataType("text").build());
        TableModel t = TableModel.builder().oid(1).schemaName("ref").name("pairs").columns(cols)
                .referenceData(data(List.of("a", "b"), List.of("integer", "text"), List.of("a", "b"), row("1", "x")))
                .build();

        String up = dataItem(engine.compare(db("a"), db("b", t), true)).getUpSql();

        assertEquals("INSERT INTO ref.pairs (a, b) OVERRIDING SYSTEM VALUE VALUES ('1'::integer, 'x'::text)"
                + " ON CONFLICT (a, b) DO NOTHING;", up);
    }

    @Test
    void changedPrimaryKeyIsNotCompared() {
        TableModel head = statuses(data(COLS, TYPES, List.of("id"), row("1", "new", "New")));
        TableModel live = statuses(data(COLS, TYPES, List.of("code"), row("1", "new", "Changed")));

        ChangeItem item = dataItem(engine.diff(db("db1", head), db("db1", live)));

        assertTrue(item.getUpSql().startsWith("-- MANUAL ACTION REQUIRED"), item.getUpSql());
        assertFalse(item.getWarnings().isEmpty());
    }

    @Test
    void canonicalJsonAndHash() {
        List<List<String>> rows = List.of(row("1", "a\"b\\c\n", null));
        assertEquals("[[\"1\",\"a\\\"b\\\\c\\n\",null]]", ReferenceDataModel.canonicalJson(rows));
        assertEquals(64, ReferenceDataModel.hash(rows).length());
        assertNotEquals(ReferenceDataModel.hash(rows), ReferenceDataModel.hash(List.of(row("1", "x", null))));
    }

    @Test
    void verificationIgnoresRowsOfTablesTheReferenceDoesNotTrack() {
        DatabaseSnapshot commit = db("db1", statuses(null));
        DatabaseSnapshot live = db("db1", statuses(data(COLS, TYPES, List.of("id"), row("1", "new", "New"))));

        assertTrue(engine.diff(commit, live).isHasChanges());
        assertFalse(engine.diff(commit, SnapshotFilter.withoutDataUntrackedIn(commit, live)).isHasChanges());
    }
}
