package com.vodianoy.pgvc.diff;

import com.vodianoy.pgvc.model.schema.ColumnModel;
import com.vodianoy.pgvc.model.schema.DatabaseSnapshot;
import com.vodianoy.pgvc.model.schema.SequenceModel;
import com.vodianoy.pgvc.model.schema.TableModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.9.1: {@code CREATE TABLE mdc.mnemo_schemes (LIKE scada.mnemo_schemes INCLUDING ALL)} leaves the copy with
 * {@code DEFAULT nextval('scada.mnemo_schemes_id_seq')}: the working tree warns and offers a quick fix.
 */
class CrossSchemaSequenceCheckTest {

    private final SchemaDiffEngine engine = new SchemaDiffEngine();

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    private static TableModel table(long oid, String schema, String name, String idDefault) {
        Map<String, ColumnModel> cols = new LinkedHashMap<>();
        cols.put("id", ColumnModel.builder().attnum(1).name("id").dataType("integer").nullable(false).defaultValue(idDefault).build());
        cols.put("title", ColumnModel.builder().attnum(2).name("title").dataType("text").nullable(true).build());
        return TableModel.builder().oid(oid).schemaName(schema).name(name).columns(cols).build();
    }

    private static SequenceModel sequence(long oid, String schema, String name, String ownedBy) {
        return SequenceModel.builder().oid(oid).schemaName(schema).name(name).dataType("integer").startValue(1).incrementBy(1)
                .minValue(1).maxValue(2147483647).cacheSize(1).ownedByTable(ownedBy).ownedByColumn(ownedBy == null ? null : "id")
                .build();
    }

    private static DatabaseSnapshot snapshot(List<TableModel> tables, List<SequenceModel> sequences) {
        Map<String, TableModel> t = new LinkedHashMap<>();
        tables.forEach(x -> t.put(x.getQualifiedName(), x));
        Map<String, SequenceModel> s = new LinkedHashMap<>();
        sequences.forEach(x -> s.put(x.getQualifiedName(), x));
        return DatabaseSnapshot.builder().sourceDatabaseId("sys:1@dev").serverVersion(160000).schemas(List.of("scada", "mdc"))
                .tables(t).sequences(s).build();
    }

    private static final TableModel SCADA = table(10, "scada", "mnemo_schemes", "nextval('scada.mnemo_schemes_id_seq'::regclass)");
    private static final SequenceModel SCADA_SEQ = sequence(11, "scada", "mnemo_schemes_id_seq", "scada.mnemo_schemes");

    @Test
    void aCopyByCreateTableLikeIsReportedWithAQuickFix() {
        TableModel copy = table(20, "mdc", "mnemo_schemes", "nextval('scada.mnemo_schemes_id_seq'::regclass)");
        DatabaseSnapshot head = snapshot(List.of(SCADA), List.of(SCADA_SEQ));
        DatabaseSnapshot live = snapshot(List.of(SCADA, copy), List.of(SCADA_SEQ));

        SchemaDiffResult r = engine.diff(head, live);
        CrossSchemaSequenceCheck.apply(r, live);

        ChangeItem added = r.getChanges().stream().filter(c -> c.getObjectName().equals("mdc.mnemo_schemes")).findFirst().orElseThrow();
        assertThat(added.getWarnings()).anySatisfy(w -> assertThat(w)
                .startsWith("⚠️ Column id of mdc.mnemo_schemes uses the sequence of another schema (scada.mnemo_schemes_id_seq),"
                        + " possibly copied by CREATE TABLE LIKE!")
                .contains("scada.mnemo_schemes_id_seq belongs to scada.mnemo_schemes"));
        assertThat(added.getQuickFixes()).singleElement().satisfies(f -> {
            assertThat(f.kind()).isEqualTo(CrossSchemaSequenceCheck.KIND);
            assertThat(f.objectName()).isEqualTo("mdc.mnemo_schemes.id");
            assertThat(f.sql()).isEqualTo("CREATE SEQUENCE IF NOT EXISTS mdc.mnemo_schemes_id_seq AS integer;\n"
                    + "ALTER SEQUENCE mdc.mnemo_schemes_id_seq OWNED BY mdc.mnemo_schemes.id;\n"
                    + "SELECT pg_catalog.setval('mdc.mnemo_schemes_id_seq'::regclass, COALESCE((SELECT max(id) FROM mdc.mnemo_schemes), 0) + 1, false);\n"
                    + "ALTER TABLE mdc.mnemo_schemes ALTER COLUMN id SET DEFAULT nextval('mdc.mnemo_schemes_id_seq'::regclass);");
        });
        assertThat(r.getWarnings()).anySatisfy(w -> assertThat(w).contains("mdc.mnemo_schemes"));
        // the table of scada itself is fine
        assertThat(CrossSchemaSequenceCheck.quickFixes(live)).hasSize(1);
    }

    @Test
    void anOwnSequenceOfTheSameNameIsUsedByTheFix() {
        TableModel copy = table(20, "mdc", "mnemo_schemes", "nextval('scada.mnemo_schemes_id_seq'::regclass)");
        SequenceModel own = sequence(21, "mdc", "mnemo_schemes_id_seq", "mdc.mnemo_schemes");
        DatabaseSnapshot live = snapshot(List.of(SCADA, copy), List.of(SCADA_SEQ, own));

        List<QuickFix> fixes = CrossSchemaSequenceCheck.quickFixes(live);

        assertThat(fixes).singleElement().satisfies(f -> {
            assertThat(f.message()).contains("Schema mdc has its own sequence mdc.mnemo_schemes_id_seq");
            assertThat(f.sql()).doesNotContain("CREATE SEQUENCE").doesNotContain("OWNED BY")
                    .endsWith("ALTER TABLE mdc.mnemo_schemes ALTER COLUMN id SET DEFAULT nextval('mdc.mnemo_schemes_id_seq'::regclass);");
        });
    }

    @Test
    void aDroppedSourceSequenceIsReportedOnTheTableEvenWithoutAChangeOfIt() {
        TableModel copy = table(20, "mdc", "mnemo_schemes", "nextval('scada.mnemo_schemes_id_seq'::regclass)");
        DatabaseSnapshot head = snapshot(List.of(copy), List.of(SCADA_SEQ));
        DatabaseSnapshot live = snapshot(List.of(copy), List.of());

        SchemaDiffResult r = engine.diff(head, live);
        CrossSchemaSequenceCheck.apply(r, live);

        // the only change is the dropped sequence: the fix goes there
        ChangeItem dropped = r.getChanges().stream().filter(c -> c.getObjectType() == ObjectType.SEQUENCE).findFirst().orElseThrow();
        assertThat(dropped.getQuickFixes()).singleElement()
                .satisfies(f -> assertThat(f.message()).contains("Sequence scada.mnemo_schemes_id_seq no longer exists"));

        // nothing changed at all: a diff-level fix
        SchemaDiffResult clean = engine.diff(live, live);
        CrossSchemaSequenceCheck.apply(clean, live);
        assertThat(clean.getQuickFixes()).hasSize(1);
    }

    @Test
    void aSharedGeneratorAndOwnSchemaDefaultsAreNotReported() {
        // a global id generator in a schema of its own, owned by nobody
        TableModel a = table(30, "mdc", "events", "nextval('ids.global_seq'::regclass)");
        SequenceModel global = sequence(31, "ids", "global_seq", null);
        // the default of the table's own schema, unqualified public sequence of a public table
        TableModel b = table(32, "scada", "alarms", "nextval('scada.alarms_id_seq'::regclass)");
        TableModel c = table(33, "public", "logs", "nextval('logs_id_seq'::regclass)");
        DatabaseSnapshot live = snapshot(List.of(a, b, c), List.of(global,
                sequence(34, "scada", "alarms_id_seq", "scada.alarms"), sequence(35, "public", "logs_id_seq", "public.logs")));

        assertThat(CrossSchemaSequenceCheck.quickFixes(live)).isEmpty();
    }

    @Test
    void quotedNamesAndRussianTexts() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("ru"));
        assertThat(CrossSchemaSequenceCheck.parseName("\"Scada\".\"Mnemo \"\"x\"\" seq\"")).containsExactly("Scada", "Mnemo \"x\" seq");
        assertThat(CrossSchemaSequenceCheck.parseName("Scada.Seq")).containsExactly("scada", "seq");

        TableModel copy = table(20, "mdc", "mnemo_schemes", "nextval('scada.mnemo_schemes_id_seq'::regclass)");
        List<QuickFix> fixes = CrossSchemaSequenceCheck.quickFixes(snapshot(List.of(SCADA, copy), List.of(SCADA_SEQ)));
        assertThat(fixes).singleElement().satisfies(f -> assertThat(f.message()).startsWith(
                "⚠️ Колонка id таблицы mdc.mnemo_schemes ссылается на последовательность из другой схемы"
                        + " (scada.mnemo_schemes_id_seq), возможно после CREATE TABLE LIKE!"));
    }
}
