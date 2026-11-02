package com.vodianoy.pgvc.matrix;

import com.vodianoy.pgvc.common.ApiException;
import com.vodianoy.pgvc.diff.DiffIgnoreRules;
import com.vodianoy.pgvc.diff.ObjectType;
import com.vodianoy.pgvc.snapshot.SnapshotBundleDto;
import com.vodianoy.pgvc.support.TestDatabases;
import com.vodianoy.pgvc.support.TestDatabases.Services;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static com.vodianoy.pgvc.support.TestDatabases.envId;
import static com.vodianoy.pgvc.support.TestDatabases.recreate;
import static com.vodianoy.pgvc.support.TestDatabases.sql;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Three live databases and one uploaded snapshot compared with a baseline. */
@EnabledIfEnvironmentVariable(named = "PGVC_TEST_JDBC_URL", matches = ".+")
class MatrixCompareIntegrationTest {

    private static final String BASE_DB = "pgvc_step11_matrix_base";
    private static final String SAME_DB = "pgvc_step11_matrix_same";
    private static final String DIFF_DB = "pgvc_step11_matrix_diff";

    private static final String SCHEMA = """
            CREATE TABLE public.customers (id bigint PRIMARY KEY, name text NOT NULL);
            CREATE TABLE public.orders (id bigint PRIMARY KEY, customer_id bigint REFERENCES public.customers(id),
                                        amount numeric(12,2));
            CREATE VIEW public.big_orders AS SELECT id, amount FROM public.orders WHERE amount > 1000;
            CREATE FUNCTION public.answer() RETURNS integer LANGUAGE sql AS $$ SELECT 42 $$;
            """;

    @TempDir
    Path dir;

    private Services s;
    private final String base = "env:" + envId(BASE_DB);
    private final String same = "env:" + envId(SAME_DB);
    private final String diff = "env:" + envId(DIFF_DB);

    @BeforeEach
    void setUp() throws Exception {
        for (String db : List.of(BASE_DB, SAME_DB, DIFF_DB)) {
            recreate(db);
            sql(db, SCHEMA);
        }
        // the third database: one table missing, one column changed, an extra table of its own, a tmp table
        sql(DIFF_DB, "DROP VIEW public.big_orders");
        sql(DIFF_DB, "ALTER TABLE public.orders ALTER COLUMN amount TYPE numeric(10,2)");
        sql(DIFF_DB, "CREATE VIEW public.big_orders AS SELECT id, amount FROM public.orders WHERE amount > 1000");
        sql(DIFF_DB, "CREATE TABLE public.local_audit (id int)");
        sql(DIFF_DB, "CREATE TABLE public.tmp_load (id int)");
        sql(DIFF_DB, "DROP FUNCTION public.answer()");
        s = new Services(dir, BASE_DB, SAME_DB, DIFF_DB);
    }

    @AfterEach
    void tearDown() {
        s.close();
    }

    private MatrixRowDto row(MatrixCompareResponse r, ObjectType type, String name) {
        return r.rows().stream().filter(x -> x.type() == type && x.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no row " + type + " " + name + " in " + r.rows()));
    }

    @Test
    void everyDatabaseIsComparedWithTheBaseline() throws Exception {
        SnapshotBundleDto exported = s.snapshots.export(envId(BASE_DB), null).bundle();
        // as uploaded from a file
        SnapshotBundleDto uploaded = TestDatabases.objectMapper().readValue(
                TestDatabases.objectMapper().writeValueAsBytes(exported), SnapshotBundleDto.class);

        MatrixCompareResponse r = s.matrix.compare(new MatrixCompareRequest(
                List.of(envId(BASE_DB), envId(SAME_DB), envId(DIFF_DB)),
                List.of(new MatrixSnapshotUpload("base.pgsnap.json", uploaded)), base,
                new DiffIgnoreRules(false, true, true, false, List.of("tmp_%"))));

        assertThat(r.baselineId()).isEqualTo(base);
        assertThat(r.databases()).extracting(MatrixDatabaseDto::id).containsExactly(base, same, diff, "snapshot:0");
        assertThat(r.databases().get(0).baseline()).isTrue();

        MatrixRowDto orders = row(r, ObjectType.TABLE, "public.orders");
        assertThat(orders.differs()).isTrue();
        assertThat(orders.cells().get(base).status()).isEqualTo(MatrixCellStatus.IDENTICAL);
        assertThat(orders.cells().get(same).status()).isEqualTo(MatrixCellStatus.IDENTICAL);
        assertThat(orders.cells().get("snapshot:0").status()).isEqualTo(MatrixCellStatus.IDENTICAL);
        MatrixCellDto changed = orders.cells().get(diff);
        assertThat(changed.status()).isEqualTo(MatrixCellStatus.MODIFIED);
        assertThat(changed.differences()).anyMatch(d -> d.contains("public.orders.amount"));
        assertThat(changed.upSql()).contains("ALTER COLUMN amount TYPE numeric(12,2)");

        MatrixCellDto missing = row(r, ObjectType.FUNCTION, "public.answer()").cells().get(diff);
        assertThat(missing.status()).isEqualTo(MatrixCellStatus.MISSING);
        assertThat(missing.upSql()).contains("CREATE OR REPLACE FUNCTION public.answer()");

        MatrixRowDto extra = row(r, ObjectType.TABLE, "public.local_audit");
        assertThat(extra.cells().get(diff).status()).isEqualTo(MatrixCellStatus.EXTRA_ONLY_HERE);
        assertThat(extra.cells().get(base).status()).isEqualTo(MatrixCellStatus.ABSENT);
        assertThat(extra.cells().get(same).status()).isEqualTo(MatrixCellStatus.ABSENT);
        assertThat(extra.cells().get(diff).upSql()).contains("DROP TABLE public.local_audit;");

        assertThat(row(r, ObjectType.TABLE, "public.customers").differs()).isFalse();
        // tmp_ objects are ignored
        assertThat(r.rows()).noneMatch(x -> x.name().contains("tmp_load"));

        MatrixDatabaseDto diffDb = r.databases().get(2);
        assertThat(diffDb.modified()).isGreaterThanOrEqualTo(1);
        assertThat(diffDb.missing()).isEqualTo(1);
        assertThat(diffDb.extra()).isEqualTo(1);
        assertThat(r.databases().get(1).modified() + r.databases().get(1).missing() + r.databases().get(1).extra())
                .isZero();
    }

    @Test
    void snapshotCanBeTheBaselineAndTooFewDatabasesAreRejected() throws Exception {
        SnapshotBundleDto exported = s.snapshots.export(envId(DIFF_DB), null).bundle();

        MatrixCompareResponse r = s.matrix.compare(new MatrixCompareRequest(List.of(envId(BASE_DB)),
                List.of(new MatrixSnapshotUpload("", exported)), "snapshot:0", null));

        assertThat(r.baselineId()).isEqualTo("snapshot:0");
        assertThat(r.databases().get(1).name()).contains(DIFF_DB);
        // the default rules do not exclude tmp_: from the snapshot's view the base lacks local_audit and tmp_load
        assertThat(row(r, ObjectType.TABLE, "public.tmp_load").cells().get(base).status())
                .isEqualTo(MatrixCellStatus.MISSING);
        assertThat(row(r, ObjectType.FUNCTION, "public.answer()").cells().get(base).status())
                .isEqualTo(MatrixCellStatus.EXTRA_ONLY_HERE);

        assertThatThrownBy(() -> s.matrix.compare(new MatrixCompareRequest(List.of(envId(BASE_DB)), List.of(), null, null)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> s.matrix.compare(new MatrixCompareRequest(List.of(envId(BASE_DB), envId(SAME_DB)),
                List.of(), "env:nope", null))).isInstanceOf(ApiException.class);
    }
}
