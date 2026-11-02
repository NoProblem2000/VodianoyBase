package com.vodianoy.pgvc.vcs;

import com.vodianoy.pgvc.common.ApiException;
import com.vodianoy.pgvc.diff.ChangeItem;
import com.vodianoy.pgvc.diff.ChangeType;
import com.vodianoy.pgvc.diff.SchemaDiffResult;
import com.vodianoy.pgvc.model.schema.ColumnModel;
import com.vodianoy.pgvc.model.schema.DatabaseSnapshot;
import com.vodianoy.pgvc.support.TestDatabases.Services;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** v1.7.1: discarding uncommitted changes, and jsonb / json / uuid / array columns between two databases. */
@EnabledIfEnvironmentVariable(named = "PGVC_TEST_JDBC_URL", matches = ".+")
class DiscardAndJsonbIntegrationTest {

    private static final String DEV_DB = "pgvc_fix171_dev";
    private static final String STAGE_DB = "pgvc_fix171_stage";

    private static final String DOCS = """
            CREATE TABLE public.docs (
                id      bigserial PRIMARY KEY,
                payload jsonb NOT NULL DEFAULT '{}'::jsonb,
                tags    jsonb DEFAULT '[]'::jsonb,
                raw     json,
                uid     uuid,
                labels  text[] DEFAULT '{}'::text[],
                created timestamptz DEFAULT now(),
                CONSTRAINT payload_has_id CHECK (payload ? 'id')
            );
            CREATE INDEX docs_payload_gin ON public.docs USING gin (payload jsonb_path_ops);
            CREATE INDEX docs_has_x ON public.docs ((payload ? 'x'));
            CREATE VIEW public.v_docs AS SELECT id, payload -> 'a' AS a FROM public.docs WHERE payload ? 'a';
            """;

    @TempDir
    Path dir;

    private Services s;
    private final String dev = envId(DEV_DB);
    private final String stage = envId(STAGE_DB);

    @BeforeEach
    void setUp() throws Exception {
        recreate(DEV_DB);
        recreate(STAGE_DB);
        sql(DEV_DB, DOCS);
        s = new Services(dir, DEV_DB, STAGE_DB);
        s.storage.initializeEnvironment(dev);
        s.vc.commitWorkingTree(dev, new CommitRequest("Docs", "dev", null, null, null));
    }

    @AfterEach
    void tearDown() {
        s.close();
    }

    // ------------------------------------------------------------------ bug 1: discard

    @Test
    void discardAllReturnsTheDatabaseToHead() throws Exception {
        sql(DEV_DB, "CREATE TABLE public.scratch (id int PRIMARY KEY, note text)");
        sql(DEV_DB, "ALTER TABLE public.docs ADD COLUMN meta jsonb NOT NULL DEFAULT '{\"v\": 1}'::jsonb");
        sql(DEV_DB, "ALTER TABLE public.docs DROP COLUMN tags");
        sql(DEV_DB, "CREATE INDEX docs_uid_idx ON public.docs (uid)");
        assertThat(s.storage.readAuditLog(dev)).isNotEmpty();

        DiscardPlan plan = s.vc.discardPlan(new DiscardPlanRequest(dev, null));
        assertThat(plan.full()).isTrue();
        assertThat(plan.totalChanges()).isEqualTo(plan.changes().size()).isGreaterThanOrEqualTo(4);
        assertThat(plan.downSql()).contains("DROP TABLE public.scratch;", "DROP COLUMN meta",
                "ADD COLUMN tags jsonb DEFAULT '[]'::jsonb", "DROP INDEX public.docs_uid_idx;");
        assertThat(plan.safety().overall().isCausesDataLoss()).isTrue();

        assertThatThrownBy(() -> s.vc.discardWorkingTree(new DiscardRequest(dev, null, null, 1, false, null, null)))
                .isInstanceOf(ApiException.class);

        DiscardResult result = s.vc.discardWorkingTree(new DiscardRequest(dev, null, null, plan.totalChanges(), false,
                null, null));
        assertThat(result.full()).isTrue();
        assertThat(result.verified()).isTrue();
        assertThat(result.remainingChanges()).isZero();
        assertThat(s.vc.getWorkingTreeStatus(dev).diff().isHasChanges()).isFalse();
        assertThat(s.storage.readAuditLog(dev)).isEmpty();
        assertThat(query(DEV_DB, "SELECT to_regclass('public.scratch')")).isNull();

        assertThatThrownBy(() -> s.vc.discardWorkingTree(new DiscardRequest(dev, null))).isInstanceOf(ApiException.class);
    }

    @Test
    void discardOneChangeKeepsTheOthersAndTheirAuditEntries() throws Exception {
        sql(DEV_DB, "ALTER TABLE public.docs ADD COLUMN keep_me text");
        sql(DEV_DB, "CREATE TABLE public.scratch (id int)");
        List<ChangeItem> changes = s.vc.getWorkingTreeStatus(dev).diff().getChanges();
        int scratch = indexOf(changes, "public.scratch");
        int audit = s.storage.readAuditLog(dev).size();

        DiscardPlan plan = s.vc.discardPlan(new DiscardPlanRequest(dev, List.of(scratch)));
        assertThat(plan.full()).isFalse();
        assertThat(plan.downSql().strip()).isEqualTo("DROP TABLE public.scratch;");

        DiscardResult result = s.vc.discardWorkingTree(new DiscardRequest(dev, List.of(scratch)));
        assertThat(result.full()).isFalse();
        assertThat(result.remainingChanges()).isEqualTo(1);
        List<ChangeItem> left = s.vc.getWorkingTreeStatus(dev).diff().getChanges();
        assertThat(left).extracting(ChangeItem::getObjectName).containsExactly("public.docs.keep_me");
        // the user's own DDL stays in the log, the DROP TABLE of the discard does not
        assertThat(s.storage.readAuditLog(dev)).hasSize(audit);

        assertThatThrownBy(() -> s.vc.discardPlan(new DiscardPlanRequest(dev, List.of(7)))).isInstanceOf(ApiException.class);
    }

    @Test
    void discardWithoutCommitsDropsWhatWasCreatedAndTheLogCanBeClearedAlone() throws Exception {
        s.storage.initializeEnvironment(stage);
        sql(STAGE_DB, "CREATE SCHEMA app; CREATE TABLE app.t (id int); CREATE VIEW app.v AS SELECT id FROM app.t");

        DiscardPlan plan = s.vc.discardPlan(new DiscardPlanRequest(stage, null));
        assertThat(plan.head()).isNull();
        assertThat(plan.downSql()).contains("DROP VIEW IF EXISTS app.v;", "DROP TABLE app.t;", "DROP SCHEMA app;");
        assertThat(plan.downSql().indexOf("DROP VIEW")).isLessThan(plan.downSql().indexOf("DROP TABLE"));

        // just the stale DDL events
        assertThat(s.vc.clearDdlLog(stage).removed()).isPositive();
        assertThat(s.storage.readAuditLog(stage)).isEmpty();
        assertThat(query(STAGE_DB, "SELECT to_regclass('app.t')")).isEqualTo("app.t");

        s.vc.discardWorkingTree(new DiscardRequest(stage, null));
        assertThat(query(STAGE_DB, "SELECT count(*) FROM pg_namespace WHERE nspname = 'app'")).isEqualTo("0");
    }

    private static int indexOf(List<ChangeItem> changes, String name) {
        for (int i = 0; i < changes.size(); i++) {
            if (changes.get(i).getObjectName().equals(name)) {
                return i;
            }
        }
        throw new AssertionError(name + " not in " + changes);
    }

    // ------------------------------------------------------------------ bug 2: jsonb between two databases

    @Test
    void jsonbColumnsArePushedAndComparedByName() throws Exception {
        s.vc.pushCommitsToEnvironment(new PushRequest(dev, stage, null, false, false));

        sql(DEV_DB, "ALTER TABLE public.docs ADD COLUMN meta jsonb NOT NULL DEFAULT '{\"v\": 1}'::jsonb");
        sql(DEV_DB, "ALTER TABLE public.docs ADD COLUMN extra json DEFAULT '[]'");
        sql(DEV_DB, "ALTER TABLE public.docs ALTER COLUMN raw TYPE jsonb USING raw::jsonb");
        DatabaseSnapshot live = s.introspector.introspect(dev);
        ColumnModel meta = live.getTables().get("public.docs").getColumns().get("meta");
        assertThat(meta.getDataType()).isEqualTo("jsonb");
        assertThat(meta.getDefaultValue()).isEqualTo("'{\"v\": 1}'::jsonb");
        assertThat(live.getTables().get("public.docs").getColumns().get("labels").getDataType()).isEqualTo("text[]");
        assertThat(live.getSourceDatabaseId()).endsWith("@" + dev);

        SchemaDiffResult wt = s.vc.getWorkingTreeStatus(dev).diff();
        assertThat(wt.getUpSql()).contains("ADD COLUMN meta jsonb DEFAULT '{\"v\": 1}'::jsonb NOT NULL;",
                "ADD COLUMN extra json DEFAULT '[]'::json;", "ALTER COLUMN raw TYPE jsonb USING raw::jsonb;");
        s.vc.commitWorkingTree(dev, new CommitRequest("jsonb columns", "dev", null, null, null));

        EnvironmentComparison cmp = s.vc.compareEnvironments(dev, stage);
        assertThat(cmp.schemaDiff().getChanges()).extracting(ChangeItem::getChangeType, ChangeItem::getObjectName)
                .contains(org.assertj.core.groups.Tuple.tuple(ChangeType.ADDED, "public.docs.meta"),
                        org.assertj.core.groups.Tuple.tuple(ChangeType.ADDED, "public.docs.extra"));
        assertThat(cmp.schemaDiff().getChanges()).noneMatch(c -> c.getChangeType() == ChangeType.RENAMED);

        PushResult pushed = s.vc.pushCommitsToEnvironment(new PushRequest(dev, stage, null, false, false));
        assertThat(pushed.verified()).isTrue();
        assertThat(query(STAGE_DB, "SELECT format_type(atttypid, atttypmod) FROM pg_attribute"
                + " WHERE attrelid = 'public.docs'::regclass AND attname = 'meta'")).isEqualTo("jsonb");
        assertThat(query(STAGE_DB, "SELECT pg_get_expr(adbin, adrelid) FROM pg_attrdef d JOIN pg_attribute a"
                + " ON a.attrelid = d.adrelid AND a.attnum = d.adnum WHERE d.adrelid = 'public.docs'::regclass"
                + " AND a.attname = 'meta'")).isEqualTo("'{\"v\": 1}'::jsonb");
        assertThat(s.engine.diffAcrossEnvironments(s.introspector.introspect(stage), s.introspector.introspect(dev))
                .getChanges()).isEmpty();
        assertThat(s.vc.getWorkingTreeStatus(stage).diff().isHasChanges()).isFalse();
    }
}
