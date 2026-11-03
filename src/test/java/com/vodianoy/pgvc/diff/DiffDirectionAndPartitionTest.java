package com.vodianoy.pgvc.diff;

import com.vodianoy.pgvc.model.schema.ColumnModel;
import com.vodianoy.pgvc.model.schema.ConstraintModel;
import com.vodianoy.pgvc.model.schema.ConstraintType;
import com.vodianoy.pgvc.model.schema.DatabaseSnapshot;
import com.vodianoy.pgvc.model.schema.SequenceModel;
import com.vodianoy.pgvc.model.schema.TableModel;
import com.vodianoy.pgvc.model.schema.ViewDependency;
import com.vodianoy.pgvc.model.schema.ViewModel;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.8.1: the direction of UP / DOWN in every kind of comparison, and tables whose partitioning changes (they are
 * recreated with their rows instead of "MANUAL ACTION REQUIRED", which used to leave a script that adds a PRIMARY KEY
 * without the partition column to a still partitioned table).
 */
class DiffDirectionAndPartitionTest {

    private final SchemaDiffEngine engine = new SchemaDiffEngine();

    // ------------------------------------------------------------------ builders

    private static ColumnModel col(int attnum, String name, String type, boolean nullable) {
        return ColumnModel.builder().attnum(attnum).name(name).dataType(type).nullable(nullable).build();
    }

    private static TableModel table(long oid, String name, ColumnModel... columns) {
        Map<String, ColumnModel> cols = new LinkedHashMap<>();
        for (ColumnModel c : columns) {
            cols.put(c.getName(), c);
        }
        return TableModel.builder().oid(oid).schemaName("public").name(name).columns(cols).build();
    }

    private static TableModel partitioned(TableModel t, String keyDef) {
        t.setPartitioned(true);
        t.setPartitionKeyDef(keyDef);
        return t;
    }

    private static TableModel partition(long oid, String name, String parent, String bound) {
        return TableModel.builder().oid(oid).schemaName("public").name(name).parentTable(parent)
                .partitionBoundExpr(bound).build();
    }

    private static TableModel with(TableModel t, ConstraintModel... constraints) {
        for (ConstraintModel c : constraints) {
            t.getConstraints().put(c.getName(), c);
        }
        return t;
    }

    private static ConstraintModel pk(long oid, String name, String... columns) {
        return ConstraintModel.builder().oid(oid).name(name).type(ConstraintType.PRIMARY_KEY)
                .definition("PRIMARY KEY (" + String.join(", ", columns) + ")").columns(List.of(columns)).build();
    }

    private static ConstraintModel fk(long oid, String name, String column, String referenced, String referencedColumn) {
        return ConstraintModel.builder().oid(oid).name(name).type(ConstraintType.FOREIGN_KEY)
                .definition("FOREIGN KEY (" + column + ") REFERENCES " + referenced + "(" + referencedColumn + ")")
                .columns(List.of(column)).referencedTable(referenced).referencedColumns(List.of(referencedColumn)).build();
    }

    private static DatabaseSnapshot snapshot(String db, List<TableModel> tables, List<ViewModel> views,
                                             List<SequenceModel> sequences) {
        Map<String, TableModel> t = new LinkedHashMap<>();
        tables.forEach(x -> t.put(x.getQualifiedName(), x));
        Map<String, ViewModel> v = new LinkedHashMap<>();
        views.forEach(x -> v.put(x.getQualifiedName(), x));
        Map<String, SequenceModel> s = new LinkedHashMap<>();
        sequences.forEach(x -> s.put(x.getQualifiedName(), x));
        return DatabaseSnapshot.builder().sourceDatabaseId(db).serverVersion(160000).schemas(List.of("public"))
                .tables(t).views(v).sequences(s).build();
    }

    private static DatabaseSnapshot snapshot(String db, TableModel... tables) {
        return snapshot(db, List.of(tables), List.of(), List.of());
    }

    private static void assertOrdered(String sql, String... parts) {
        int last = -1;
        for (String p : parts) {
            int idx = sql.indexOf(p, last + 1);
            assertThat(idx).as("missing after position %d: %s%n%s", last, p, sql).isGreaterThan(last);
            last = idx;
        }
    }

    private static TableModel customers(long oid, boolean withEmail) {
        return withEmail
                ? with(table(oid, "customers", col(1, "id", "bigint", false), col(2, "email", "text", true)), pk(oid + 1, "customers_pkey", "id"))
                : with(table(oid, "customers", col(1, "id", "bigint", false)), pk(oid + 1, "customers_pkey", "id"));
    }

    // ------------------------------------------------------------------ direction

    @Test
    void compareBringsTheCurrentDatabaseToTheTarget() {
        DatabaseSnapshot target = snapshot("sys:1@dev", customers(100, true)); // the reference (source / .pgsnap.json)
        DatabaseSnapshot current = snapshot("sys:2@prod", customers(500, false)); // the database that is changed

        SchemaDiffResult r = engine.compare(current, target, true);
        assertThat(r.getChanges()).singleElement().satisfies(c -> {
            assertThat(c.getChangeType()).isEqualTo(ChangeType.ADDED);
            assertThat(c.getObjectName()).isEqualTo("public.customers.email");
            assertThat(c.isExtraInTargetDb()).isFalse();
        });
        assertThat(r.getUpSql()).contains("ALTER TABLE public.customers ADD COLUMN email text;").doesNotContain("DROP COLUMN");
        assertThat(r.getDownSql()).contains("ALTER TABLE public.customers DROP COLUMN email;");

        // the other way round the column is extra in the database that is changed: a destructive, unselected change
        SchemaDiffResult back = engine.compare(target, current, true);
        assertThat(back.getChanges()).singleElement().satisfies(c -> {
            assertThat(c.getChangeType()).isEqualTo(ChangeType.DELETED);
            assertThat(c.isExtraInTargetDb()).isTrue();
        });
        assertThat(back.getUpSql()).contains("DROP COLUMN email");
    }

    @Test
    void syncBetweenEnvironmentsAddsWhatTheTargetLacks() {
        DatabaseSnapshot sourceHead = snapshot("sys:1@dev", customers(100, true));
        DatabaseSnapshot targetLive = snapshot("sys:2@stage", customers(500, false));
        // VersionControlService.compareEnvironments: from = live target, to = source commit
        SchemaDiffResult r = engine.diffAcrossEnvironments(targetLive, sourceHead);
        assertThat(r.getUpSql()).contains("ADD COLUMN email text;").doesNotContain("DROP COLUMN");
        assertThat(r.getDownSql()).contains("DROP COLUMN email;");
    }

    @Test
    void workingTreeUpRecordsTheLiveChangeAndDownRevertsIt() {
        DatabaseSnapshot head = snapshot("sys:1@dev", customers(100, true));
        DatabaseSnapshot live = snapshot("sys:1@dev", customers(100, false)); // the developer dropped the column
        SchemaDiffResult r = engine.diff(head, live);
        assertThat(r.getChanges()).singleElement().extracting(ChangeItem::getChangeType).isEqualTo(ChangeType.DELETED);
        assertThat(r.getUpSql()).contains("DROP COLUMN email;");
        // Discard runs DOWN: the column comes back
        assertThat(r.getDownSql()).contains("ADD COLUMN email text;").doesNotContain("DROP COLUMN");
    }

    // ------------------------------------------------------------------ partitioning changes

    private static TableModel regularEvents(long oid) {
        return with(table(oid, "events", col(1, "id", "bigint", false), col(2, "date", "date", false),
                col(3, "payload", "text", true)), pk(oid + 1, "events_pkey", "id"));
    }

    private static TableModel partitionedEvents(long oid) {
        return with(partitioned(table(oid, "events", col(1, "id", "bigint", false), col(2, "date", "date", false),
                col(3, "payload", "text", true)), "RANGE (date)"), pk(oid + 1, "events_pkey", "id", "date"));
    }

    private static ViewModel eventsView(long oid) {
        return ViewModel.builder().oid(oid).schemaName("public").name("v_events")
                .definition(" SELECT id, date FROM events;").populated(true)
                .dependencies(List.of(ViewDependency.builder().relation("public.events").relationKind("r").build())).build();
    }

    @Test
    void aTableThatBecomesPartitionedIsRecreatedWithItsRows() {
        DatabaseSnapshot regular = snapshot("sys:1@dev", List.of(regularEvents(10)), List.of(eventsView(30)), List.of());
        DatabaseSnapshot part = snapshot("sys:1@dev", List.of(partitionedEvents(20),
                partition(21, "events_2026", "public.events", "FOR VALUES FROM ('2026-01-01') TO ('2027-01-01')")),
                List.of(eventsView(31)), List.of());

        SchemaDiffResult r = engine.diff(regular, part);
        assertThat(r.getUpSql()).doesNotContain("MANUAL ACTION");
        assertOrdered(r.getUpSql(),
                "CREATE TEMPORARY TABLE _pgvc_copy_0 AS SELECT * FROM public.events;",
                "DROP VIEW IF EXISTS public.v_events;",
                "DROP TABLE public.events;",
                "CREATE TABLE public.events (",
                "PARTITION BY RANGE (date);",
                "CREATE TABLE public.events_2026 PARTITION OF public.events",
                "INSERT INTO public.events (id, date, payload)\n    SELECT id, date, payload FROM pg_temp._pgvc_copy_0;",
                "DROP TABLE pg_temp._pgvc_copy_0;",
                "ALTER TABLE public.events ADD CONSTRAINT events_pkey PRIMARY KEY (id, date);",
                "CREATE OR REPLACE VIEW public.v_events");
        // the partition belongs to the recreation: one change for the table, one for the view
        assertThat(r.getChanges()).extracting(ChangeItem::getObjectName).containsExactlyInAnyOrder("public.events", "public.v_events");
        ChangeItem table = r.getChanges().stream().filter(c -> c.getObjectName().equals("public.events")).findFirst().orElseThrow();
        assertThat(table.getChangeType()).isEqualTo(ChangeType.MODIFIED);
        assertThat(table.getDetails()).contains("recreated").contains("not partitioned -> PARTITION BY RANGE (date)");
        assertThat(table.getUpSql()).contains("INSERT INTO public.events");
        assertThat(table.getDownSql()).contains("DROP TABLE public.events;").doesNotContain("PARTITION BY");

        // DOWN (= Discard when the developer converted the table): back to a regular table, PRIMARY KEY (id) on it
        String downStatements = r.getDownSql().replaceAll("(?m)^-- WARNING:.*$", "");
        assertThat(downStatements).doesNotContain("MANUAL ACTION").doesNotContain("PARTITION BY");
        assertOrdered(r.getDownSql(),
                "CREATE TEMPORARY TABLE _pgvc_copy_0 AS SELECT * FROM public.events;",
                "DROP TABLE public.events;",
                "CREATE TABLE public.events (",
                "INSERT INTO public.events (id, date, payload)",
                "ALTER TABLE public.events ADD CONSTRAINT events_pkey PRIMARY KEY (id);");
    }

    @Test
    void anotherPartitionKeyRecreatesTheTableAndItsPartitions() {
        TableModel byDate = partitionedEvents(20);
        TableModel byId = with(partitioned(table(40, "events", col(1, "id", "bigint", false), col(2, "date", "date", false),
                col(3, "payload", "text", true)), "HASH (id)"), pk(41, "events_pkey", "id"));
        DatabaseSnapshot a = snapshot("sys:1@dev", byDate,
                partition(21, "events_2026", "public.events", "FOR VALUES FROM ('2026-01-01') TO ('2027-01-01')"));
        DatabaseSnapshot b = snapshot("sys:1@dev", byId,
                partition(42, "events_h0", "public.events", "FOR VALUES WITH (modulus 2, remainder 0)"),
                partition(43, "events_h1", "public.events", "FOR VALUES WITH (modulus 2, remainder 1)"));

        SchemaDiffResult r = engine.diff(a, b);
        assertThat(r.getChanges()).singleElement().extracting(ChangeItem::getObjectName).isEqualTo("public.events");
        assertThat(r.getUpSql()).doesNotContain("DROP TABLE public.events_2026");
        assertOrdered(r.getUpSql(), "CREATE TEMPORARY TABLE", "DROP TABLE public.events;", "PARTITION BY HASH (id);",
                "CREATE TABLE public.events_h0 PARTITION OF", "CREATE TABLE public.events_h1 PARTITION OF",
                "INSERT INTO public.events", "PRIMARY KEY (id);");
        assertOrdered(r.getDownSql(), "DROP TABLE public.events;", "PARTITION BY RANGE (date);",
                "CREATE TABLE public.events_2026 PARTITION OF", "INSERT INTO public.events", "PRIMARY KEY (id, date);");
    }

    @Test
    void foreignKeysAndOwnedSequencesFollowARecreatedTable() {
        SequenceModel seq = SequenceModel.builder().oid(60).schemaName("public").name("events_id_seq").dataType("bigint")
                .startValue(1).incrementBy(1).minValue(1).maxValue(Long.MAX_VALUE).cacheSize(1)
                .ownedByTable("public.events").ownedByColumn("id").build();
        TableModel regular = regularEvents(10);
        regular.getColumns().get("id").setDefaultValue("nextval('events_id_seq'::regclass)");
        TableModel hashed = with(partitioned(table(20, "events", col(1, "id", "bigint", false),
                col(2, "date", "date", false), col(3, "payload", "text", true)), "HASH (id)"), pk(21, "events_pkey", "id"));
        hashed.getColumns().get("id").setDefaultValue("nextval('events_id_seq'::regclass)");
        TableModel notes = with(table(50, "notes", col(1, "id", "integer", false), col(2, "event_id", "bigint", true)),
                pk(51, "notes_pkey", "id"), fk(52, "notes_event_fk", "event_id", "public.events", "id"));

        SchemaDiffResult r = engine.diff(snapshot("sys:1@dev", List.of(regular, notes), List.of(), List.of(seq)),
                snapshot("sys:1@dev", List.of(hashed, notes.toBuilder().build()), List.of(), List.of(seq.toBuilder().build())));
        assertOrdered(r.getUpSql(),
                "CREATE TEMPORARY TABLE _pgvc_copy_0 AS SELECT * FROM public.events;",
                "ALTER SEQUENCE public.events_id_seq OWNED BY NONE;",
                "ALTER TABLE public.notes DROP CONSTRAINT notes_event_fk;",
                "DROP TABLE public.events;",
                "CREATE TABLE public.events (",
                "INSERT INTO public.events",
                "ALTER TABLE public.events ADD CONSTRAINT events_pkey PRIMARY KEY (id);",
                "ALTER TABLE public.notes ADD CONSTRAINT notes_event_fk FOREIGN KEY (event_id) REFERENCES public.events(id);",
                "ALTER SEQUENCE public.events_id_seq OWNED BY public.events.id;");
        // all of it belongs to the one change of the table
        assertThat(r.getChanges()).singleElement().satisfies(c -> assertThat(c.getUpSql())
                .contains("notes_event_fk").contains("OWNED BY NONE"));
    }

    @Test
    void identityColumnsAreCopiedWithOverridingAndTheirSequenceContinues() {
        TableModel regular = regularEvents(10);
        regular.getColumns().get("id").setIdentityGeneration("ALWAYS");
        TableModel part = partitionedEvents(20);
        part.getColumns().get("id").setIdentityGeneration("ALWAYS");
        SchemaDiffResult r = engine.diff(snapshot("sys:1@dev", regular), snapshot("sys:1@dev", part,
                partition(21, "events_2026", "public.events", "FOR VALUES FROM ('2026-01-01') TO ('2027-01-01')")));
        assertThat(r.getUpSql()).contains("INSERT INTO public.events (id, date, payload) OVERRIDING SYSTEM VALUE")
                .contains("SELECT pg_catalog.setval(pg_catalog.pg_get_serial_sequence('public.events', 'id'), m) "
                        + "FROM (SELECT max(id) AS m FROM public.events) s WHERE m IS NOT NULL;");
    }

    @Test
    void generatedColumnsAreNotCopied() {
        TableModel regular = regularEvents(10);
        regular.getColumns().put("year", ColumnModel.builder().attnum(4).name("year").dataType("integer").nullable(true)
                .generatedExpression("EXTRACT(year FROM date)").build());
        TableModel part = partitionedEvents(20);
        part.getColumns().put("year", regular.getColumns().get("year").toBuilder().build());
        SchemaDiffResult r = engine.diff(snapshot("sys:1@dev", regular), snapshot("sys:1@dev", part));
        assertThat(r.getUpSql()).contains("INSERT INTO public.events (id, date, payload)\n    SELECT id, date, payload FROM");
    }

    // ------------------------------------------------------------------ PRIMARY KEY of a partitioned table

    @Test
    void aPrimaryKeyWithoutThePartitionColumnGetsIt() {
        TableModel t = with(partitioned(table(70, "measurements", col(1, "id", "bigint", false),
                col(2, "Day", "date", false)), "RANGE (\"Day\")"), pk(71, "measurements_pkey", "id"));
        SchemaDiffResult r = engine.compare(snapshot("sys:2@prod"), snapshot("sys:1@dev", t), true);
        assertThat(r.getUpSql()).contains("ADD CONSTRAINT measurements_pkey PRIMARY KEY (id, \"Day\");");
        assertThat(r.getWarnings()).anyMatch(w -> w.contains("partition key") && w.contains("Day"));
    }

    @Test
    void aPrimaryKeyThatHasThePartitionColumnIsKeptAsItIs() {
        SchemaDiffResult r = engine.compare(snapshot("sys:2@prod"), snapshot("sys:1@dev", partitionedEvents(20)), true);
        assertThat(r.getUpSql()).contains("ADD CONSTRAINT events_pkey PRIMARY KEY (id, date);");
        assertThat(r.getWarnings()).noneMatch(w -> w.contains("partition key"));
    }

    @Test
    void partitionsDoNotRepeatTheColumnsAndKeysOfTheirParent() {
        SchemaDiffResult r = engine.compare(snapshot("sys:2@prod"), snapshot("sys:1@dev", partitionedEvents(20),
                partition(21, "events_2026", "public.events", "FOR VALUES FROM ('2026-01-01') TO ('2027-01-01')")), true);
        assertThat(r.getUpSql()).contains("CREATE TABLE public.events_2026 PARTITION OF public.events\n"
                + "    FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');");
        assertThat(r.getUpSql()).doesNotContain("ALTER TABLE public.events_2026 ADD CONSTRAINT");
    }
}
