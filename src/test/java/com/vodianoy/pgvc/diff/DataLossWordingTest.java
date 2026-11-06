package com.vodianoy.pgvc.diff;

import com.vodianoy.pgvc.model.schema.ColumnModel;
import com.vodianoy.pgvc.model.schema.DatabaseSnapshot;
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
 * v1.9.0: a dropped table / partition / column is worded by what the comparison describes - already lost in the live
 * database (working tree), lost when the script runs (sync, snapshot, discard script), or history - and every text of
 * the engine follows the language of the request.
 */
class DataLossWordingTest {

    private final SchemaDiffEngine engine = new SchemaDiffEngine();

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    private static TableModel table(long oid, String name, long rows, String size, ColumnModel... columns) {
        Map<String, ColumnModel> cols = new LinkedHashMap<>();
        for (ColumnModel c : columns) {
            cols.put(c.getName(), c);
        }
        return TableModel.builder().oid(oid).schemaName("scada").name(name).columns(cols).estimatedRows(rows)
                .prettySize(size).build();
    }

    private static ColumnModel col(int attnum, String name) {
        return ColumnModel.builder().attnum(attnum).name(name).dataType("double precision").nullable(true).build();
    }

    private static DatabaseSnapshot snapshot(TableModel... tables) {
        Map<String, TableModel> t = new LinkedHashMap<>();
        for (TableModel x : tables) {
            t.put(x.getQualifiedName(), x);
        }
        return DatabaseSnapshot.builder().sourceDatabaseId("sys:1@dev").serverVersion(160000).schemas(List.of("scada"))
                .tables(t).build();
    }

    private static final TableModel ARCHIVE = table(10, "archive_datas_double_parameters_2026_06", 1_692_900_000L, "147 GB",
            col(1, "id"), col(2, "value"));

    @Test
    void workingTreeSaysTheDataIsAlreadyLost() {
        SchemaDiffResult r = engine.diff(snapshot(ARCHIVE), snapshot());
        assertThat(r.getMode()).isEqualTo(DiffMode.APPLIED);
        ChangeItem item = r.getChanges().get(0);
        assertThat(item.getDataLoss()).isEqualTo(ChangeItem.ALREADY_LOST);
        assertThat(item.getLostRows()).isEqualTo(1_692_900_000L);
        assertThat(item.getLostPrettySize()).isEqualTo("147 GB");
        assertThat(item.getDetails()).isEqualTo("already dropped in the live database: its data is lost")
                .doesNotContain("will be lost");
        assertThat(item.getWarnings()).singleElement().asString()
                .startsWith("Table scada.archive_datas_double_parameters_2026_06 was already dropped in the live database.")
                .contains("(147 GB / 1692.9M rows) is permanently lost")
                .contains("restores only an empty structure without rows");
        // the UP SQL of the commit runs on other databases later: its comment describes running it
        assertThat(r.getUpSql()).contains("-- WARNING: Running this script drops table scada.archive_datas_double_parameters_2026_06"
                + " together with all its data (147 GB / 1692.9M rows).");
    }

    @Test
    void aScriptToRunSaysTheDataWillBeLost() {
        SchemaDiffResult r = engine.compare(snapshot(ARCHIVE), snapshot(), true);
        assertThat(r.getMode()).isEqualTo(DiffMode.PLANNED);
        ChangeItem item = r.getChanges().get(0);
        assertThat(item.getDataLoss()).isEqualTo(ChangeItem.WILL_BE_LOST);
        assertThat(item.getDetails()).isEqualTo("will be dropped together with its data");
        assertThat(item.getWarnings()).singleElement().asString()
                .isEqualTo("Running this script drops table scada.archive_datas_double_parameters_2026_06 together with all its"
                        + " data (147 GB / 1692.9M rows).");
    }

    @Test
    void theDiscardScriptOfTheWorkingTreeIsPlanned() {
        // the developer created a table: discarding it drops the rows written meanwhile
        SchemaDiffResult r = engine.diff(snapshot(), snapshot(ARCHIVE));
        assertThat(r.getChanges().get(0).getDataLoss()).isNull();
        assertThat(r.getDownSql()).contains("-- WARNING: Running this script drops table scada.archive_datas_double_parameters_2026_06");
    }

    @Test
    void historyIsWordedInThePastWithoutABadge() {
        SchemaDiffResult r = engine.diff(snapshot(ARCHIVE), snapshot(), null, DiffMode.RECORDED);
        ChangeItem item = r.getChanges().get(0);
        assertThat(item.getDataLoss()).isNull();
        assertThat(item.getDetails()).isEqualTo("dropped together with its data");
        assertThat(item.getWarnings()).singleElement().asString()
                .startsWith("Table scada.archive_datas_double_parameters_2026_06 was dropped together with its data");
    }

    @Test
    void aDroppedColumnOfTheWorkingTreeIsAlreadyLostToo() {
        TableModel before = table(10, "t", 5_000, "1 MB", col(1, "id"), col(2, "value"));
        TableModel after = table(10, "t", 5_000, "1 MB", col(1, "id"));
        SchemaDiffResult r = engine.diff(snapshot(before), snapshot(after));
        ChangeItem item = r.getChanges().get(0);
        assertThat(item.getObjectName()).isEqualTo("scada.t.value");
        assertThat(item.getDataLoss()).isEqualTo(ChangeItem.ALREADY_LOST);
        assertThat(item.getWarnings()).singleElement().asString()
                .startsWith("Column scada.t.value was already dropped in the live database. Its data (1 MB / 5k rows)");
    }

    @Test
    void russianRequestsGetRussianTextsWithoutEnglishLeftovers() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("ru"));
        SchemaDiffResult r = engine.diff(snapshot(ARCHIVE), snapshot());
        ChangeItem item = r.getChanges().get(0);
        assertThat(item.getDetails()).isEqualTo("уже удалена в живой БД: данные утеряны");
        assertThat(item.getWarnings()).singleElement().asString()
                .startsWith("Таблица scada.archive_datas_double_parameters_2026_06 уже удалена из живой БД.")
                .contains("Данные (147 GB, строк: 1692.9M) утеряны безвозвратно!")
                .contains("«Отменить изменения (сброс к HEAD)»")
                .doesNotContain("dropped").doesNotContain("lost");

        SchemaDiffResult planned = engine.compare(snapshot(ARCHIVE), snapshot(), true);
        assertThat(planned.getChanges().get(0).getDetails()).isEqualTo("будет удалена вместе с данными");
        assertThat(planned.getWarnings()).singleElement().asString().startsWith("При выполнении этого скрипта таблица scada.");

        // other changes are translated as well
        TableModel before = table(10, "t", 0, null, col(1, "id"));
        TableModel after = table(10, "t", 0, null, col(1, "id"), col(2, "value"));
        assertThat(engine.diff(snapshot(before), snapshot(after)).getChanges().get(0).getDetails())
                .isEqualTo("новая колонка double precision");
        assertThat(engine.diff(snapshot(), snapshot(after)).getChanges().get(0).getDetails()).isEqualTo("новая таблица");
    }

    @Test
    void everyTextHasBothLanguages() {
        DiffTexts en = DiffTexts.of(Locale.ENGLISH);
        DiffTexts ru = DiffTexts.of(Locale.forLanguageTag("ru"));
        assertThat(en.t("renamed", "a")).isEqualTo("renamed from a");
        assertThat(ru.t("renamed", "a")).isEqualTo("переименовано, было a");
        assertThat(DiffTexts.of(Locale.GERMAN).t("table.added")).isEqualTo("new table");
    }
}
