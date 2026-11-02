package com.vodianoy.pgvc.refdata;

import com.vodianoy.pgvc.cli.CliCommands;
import com.vodianoy.pgvc.common.ApiException;
import com.vodianoy.pgvc.diff.ChangeItem;
import com.vodianoy.pgvc.diff.ObjectType;
import com.vodianoy.pgvc.gitexport.GitExport;
import com.vodianoy.pgvc.gitexport.GitExportResult;
import com.vodianoy.pgvc.model.schema.DatabaseSnapshot;
import com.vodianoy.pgvc.model.schema.ReferenceDataModel;
import com.vodianoy.pgvc.support.TestDatabases;
import com.vodianoy.pgvc.support.TestDatabases.Services;
import com.vodianoy.pgvc.vcs.CommitRequest;
import com.vodianoy.pgvc.vcs.CommitResult;
import com.vodianoy.pgvc.vcs.ExecuteMigrationRequest;
import com.vodianoy.pgvc.vcs.PushRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.vodianoy.pgvc.support.TestDatabases.envId;
import static com.vodianoy.pgvc.support.TestDatabases.query;
import static com.vodianoy.pgvc.support.TestDatabases.recreate;
import static com.vodianoy.pgvc.support.TestDatabases.sql;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reference data end to end: tracking a dictionary, committing its rows, pushing them to another environment,
 * exporting the schema as a Git folder and the CLI commands.
 */
@EnabledIfEnvironmentVariable(named = "PGVC_TEST_JDBC_URL", matches = ".+")
class ReferenceDataIntegrationTest {

    private static final String DEV_DB = "pgvc_step12_dev";
    private static final String STAGE_DB = "pgvc_step12_stage";

    private static final String SCHEMA = """
            CREATE SCHEMA ref;
            CREATE TABLE ref.statuses (id integer PRIMARY KEY, code text NOT NULL UNIQUE, title text);
            INSERT INTO ref.statuses VALUES (1, 'new', 'New'), (2, 'paid', 'Paid'), (3, 'shipped', 'Shipped');
            CREATE TABLE ref.no_key (x integer);
            CREATE TABLE ref.orders (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                     status_id integer REFERENCES ref.statuses(id));
            CREATE VIEW ref.open_orders AS SELECT id FROM ref.orders WHERE status_id < 3;
            CREATE FUNCTION ref.answer() RETURNS integer LANGUAGE sql AS $$ SELECT 42 $$;
            CREATE FUNCTION ref.answer(integer) RETURNS integer LANGUAGE sql AS $$ SELECT $1 $$;
            CREATE FUNCTION ref.touch() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NEW; END $$;
            CREATE TRIGGER orders_touch BEFORE UPDATE ON ref.orders FOR EACH ROW EXECUTE PROCEDURE ref.touch();
            CREATE TYPE ref.mood AS ENUM ('ok', 'bad');
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
        sql(DEV_DB, SCHEMA);
        s = new Services(dir, DEV_DB, STAGE_DB);
        s.storage.initializeEnvironment(dev);
        s.vc.commitWorkingTree(dev, new CommitRequest("Baseline", "dev", null, null, null));
    }

    @AfterEach
    void tearDown() {
        s.close();
    }

    private record Cli(int code, String out, String err) {
    }

    private Cli cli(String... args) {
        Map<String, String> options = new HashMap<>();
        for (String a : args) {
            String[] kv = a.replaceFirst("^--", "").split("=", 2);
            options.put(kv[0], kv.length > 1 ? kv[1] : "");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = s.cli.run(options, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Cli(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void dictionaryRowsAreCommittedPushedAndExported() throws Exception {
        // ---- tracking: only tables with a primary key
        assertThatThrownBy(() -> s.referenceData.track(dev, new TrackTableRequest("ref.no_key", "dev")))
                .isInstanceOf(ApiException.class);
        ReferenceDataTableView tracked = s.referenceData.track(dev, new TrackTableRequest("ref.statuses", "dev"));
        assertThat(tracked.tracked()).isTrue();
        assertThat(tracked.rowCount()).isEqualTo(3L);
        assertThat(s.referenceData.list(dev)).filteredOn(ReferenceDataTableView::tracked).hasSize(1);

        // ---- the rows show up as "now tracked" and are committed as INSERT ... ON CONFLICT
        ChangeItem now = s.vc.getWorkingTreeStatus(dev).diff().getChanges().stream()
                .filter(c -> c.getObjectType() == ObjectType.DATA).findFirst().orElseThrow();
        assertThat(now.getDetails()).contains("3 row(s) added");
        s.vc.commitWorkingTree(dev, new CommitRequest("Track statuses", "dev", null, null, null));
        assertThat(s.vc.getWorkingTreeStatus(dev).diff().isHasChanges()).isFalse();

        // ---- Mode B: a data migration; the DOWN SQL is generated from the rows
        CommitResult result = s.vc.executeAndCommitSql(dev, new ExecuteMigrationRequest("Statuses v2", "dev",
                "UPDATE ref.statuses SET title = 'Paid in full' WHERE id = 2;"
                        + " INSERT INTO ref.statuses VALUES (4, 'returned', 'Returned');"
                        + " DELETE FROM ref.statuses WHERE id = 1;", null, false));
        assertThat(result.changes()).extracting(ChangeItem::getObjectType).contains(ObjectType.DATA);
        String generatedDown = s.storage.requireCommit(dev, result.commit().hash()).getDownSql();
        assertThat(generatedDown).contains("DELETE FROM ref.statuses WHERE id = '4'::integer;",
                "VALUES ('1'::integer, 'new'::text, 'New'::text) ON CONFLICT (id) DO UPDATE SET",
                "'Paid'::text");

        // ---- direct edits are drift
        sql(DEV_DB, "UPDATE ref.statuses SET title = 'Shipped!' WHERE id = 3");
        ChangeItem drift = s.vc.getWorkingTreeStatus(dev).diff().getChanges().get(0);
        assertThat(drift.getObjectType()).isEqualTo(ObjectType.DATA);
        assertThat(drift.getUpSql()).contains("'Shipped!'::text");
        Cli check = cli("--action=check-drift", "--env=" + dev, "--output=" + dir.resolve("drift.md"));
        assertThat(check.code()).isEqualTo(CliCommands.DRIFT);
        assertThat(check.out()).contains("SCHEMA DRIFT", "DATA ref.statuses");
        assertThat(Files.readString(dir.resolve("drift.md"))).contains("Schema drift detected", "| Change | Object | Risk |");
        s.vc.commitWorkingTree(dev, new CommitRequest("Shipped!", "dev", null, null, null));
        assertThat(cli("--action=check-drift", "--env=" + dev).code()).isEqualTo(CliCommands.OK);

        // ---- push: the dictionary arrives with the commits
        s.vc.pushCommitsToEnvironment(new PushRequest(dev, stage, null, false, false));
        assertThat(query(STAGE_DB, "SELECT string_agg(id || ':' || title, ',' ORDER BY id) FROM ref.statuses"))
                .isEqualTo("2:Paid in full,3:Shipped!,4:Returned");
        // stage does not track the table itself: its working tree is clean
        assertThat(s.vc.getWorkingTreeStatus(stage).diff().isHasChanges()).isFalse();

        // ---- rollback to the commit before the tracking: rows stay, the check ignores them
        String baseline = s.storage.getCommitGraph(dev).commits().stream()
                .filter(c -> c.message().equals("Baseline")).findFirst().orElseThrow().hash();
        DatabaseSnapshot live = s.introspector.introspect(dev);
        assertThat(live.getTables().get("ref.statuses").getReferenceData().getRowCount()).isEqualTo(3);
        assertThat(live.getTables().get("ref.statuses").getReferenceData().getColumns()).containsExactly("id", "code", "title");
        s.vc.rollbackToCommit(dev, new com.vodianoy.pgvc.vcs.RollbackRequest(baseline, null, false, false));
        assertThat(query(DEV_DB, "SELECT count(*) FROM ref.statuses")).isEqualTo("3");
    }

    @Test
    void gitExportWritesOneFilePerObjectAndRemovesStaleFiles() throws Exception {
        s.referenceData.track(dev, new TrackTableRequest("ref.statuses", "dev"));
        s.vc.commitWorkingTree(dev, new CommitRequest("Track statuses", "dev", null, null, null));

        GitExport export = s.gitExport.render(dev, null, true);
        assertThat(export.zipName()).startsWith("pgvc-git-export_" + dev + "_").endsWith(".zip");
        Map<String, String> files = export.files();
        assertThat(files).containsKeys("schema/schemas.sql", "schema/tables/ref.statuses.sql", "schema/tables/ref.orders.sql",
                "schema/views/ref.open_orders.sql", "schema/functions/ref.answer.sql", "schema/functions/ref.touch.sql",
                "schema/triggers/ref.orders.orders_touch.sql", "schema/enums/ref.mood.sql",
                "schema/reference_data/ref.statuses.sql");
        assertThat(files.keySet()).anyMatch(k -> k.matches("migrations/0001_[0-9a-f]{7}_baseline\\.up\\.sql"));
        assertThat(files.keySet()).anyMatch(k -> k.matches("migrations/0002_[0-9a-f]{7}_track-statuses\\.down\\.sql"));
        String table = files.get("schema/tables/ref.statuses.sql");
        assertThat(table).contains("CREATE TABLE ref.statuses", "ADD CONSTRAINT statuses_pkey PRIMARY KEY (id)")
                .doesNotContain("INSERT INTO");
        // columns in attnum order
        assertThat(table.indexOf("id integer")).isLessThan(table.indexOf("code text")).isLessThan(table.indexOf("title text"));
        assertThat(files.get("schema/functions/ref.answer.sql")).contains("ref.answer()", "ref.answer(integer)");
        assertThat(files.get("schema/triggers/ref.orders.orders_touch.sql")).contains("EXECUTE FUNCTION ref.touch();");
        assertThat(files.get("schema/reference_data/ref.statuses.sql"))
                .contains("INSERT INTO ref.statuses (id, code, title) VALUES ('1'::integer, 'new'::text, 'New'::text)");
        assertThat(files.get("schema/tables/ref.orders.sql")).doesNotContain("CREATE TRIGGER");

        // ---- deterministic: the same schema gives the same zip
        assertThat(Arrays.equals(s.gitExport.zip(export), s.gitExport.zip(s.gitExport.render(dev, null, true)))).isTrue();

        // ---- into a folder: written, then unchanged; stale generated files are removed, other files kept
        Path repo = Files.createDirectories(dir.resolve("repo"));
        Files.createDirectories(repo.resolve("schema/tables"));
        Files.writeString(repo.resolve("schema/tables/ref.gone.sql"), "-- old");
        Files.writeString(repo.resolve("README.md"), "# my repo");
        GitExportResult first = s.gitExport.writeTo(export, repo.toString());
        assertThat(first.written()).hasSize(files.size());
        assertThat(first.deleted()).containsExactly("schema/tables/ref.gone.sql");
        assertThat(repo.resolve("README.md")).exists();
        GitExportResult again = s.gitExport.writeTo(s.gitExport.render(dev, null, true), repo.toString());
        assertThat(again.written()).isEmpty();
        assertThat(again.unchanged()).isEqualTo(files.size());

        // ---- one change touches one schema file (plus the new migration)
        s.vc.executeAndCommitSql(dev, new ExecuteMigrationRequest("Add note", "dev",
                "ALTER TABLE ref.orders ADD COLUMN note text;", null, false));
        GitExportResult third = s.gitExport.writeTo(s.gitExport.render(dev, null, true), repo.toString());
        assertThat(third.written()).filteredOn(p -> p.startsWith("schema/")).containsExactly("schema/tables/ref.orders.sql");
        assertThat(third.written()).filteredOn(p -> p.startsWith("migrations/")).hasSize(2);

        assertThatThrownBy(() -> s.gitExport.writeTo(export, "relative/path")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> s.gitExport.writeTo(export, dir.resolve("missing").toString())).isInstanceOf(ApiException.class);
    }

    @Test
    void diffReportForAMergeRequest() throws Exception {
        s.storage.initializeEnvironment(stage);
        s.vc.pushCommitsToEnvironment(new PushRequest(dev, stage, null, false, false));
        s.vc.executeAndCommitSql(dev, new ExecuteMigrationRequest("Drop title, add rank", "dev",
                "ALTER TABLE ref.statuses DROP COLUMN title; ALTER TABLE ref.statuses ADD COLUMN rank integer;", null, false));
        Path snapshot = dir.resolve("dev.pgsnap.json");
        Files.write(snapshot, TestDatabases.objectMapper().writeValueAsBytes(s.snapshots.export(dev, null).bundle()));

        Path report = dir.resolve("pr-comment.md");
        Cli cli = cli("--action=diff-report", "--env=" + stage, "--snapshot=" + snapshot, "--output=" + report,
                "--fail-on=dangerous");
        assertThat(cli.code()).as(cli.err()).isEqualTo(CliCommands.DRIFT);
        String md = Files.readString(report);
        assertThat(md).contains("Schema diff: " + STAGE_DB + " → dev.pgsnap.json", "| Change | Object | Risk |",
                "`ref.statuses.title`", "🔴 DANGEROUS", "<summary>UP SQL</summary>", "DROP COLUMN title");
        assertThat(cli.out()).contains("report written to");

        assertThat(cli("--action=diff-report", "--env=" + stage, "--snapshot=" + snapshot).code()).isEqualTo(CliCommands.OK);
        assertThat(cli("--action=check-drift").code()).isEqualTo(CliCommands.ERROR);
        assertThat(cli("--action=check-drift", "--env=nope").err()).contains("unknown environment", dev);
        assertThat(cli("--action=wat").code()).isEqualTo(CliCommands.ERROR);
        assertThat(cli().out()).contains("--action=check-drift");
        assertThat(ReferenceDataModel.MAX_ROWS).isEqualTo(10_000);
        assertThat(List.of(CliCommands.OK, CliCommands.ERROR, CliCommands.DRIFT)).containsExactly(0, 1, 2);
    }
}
