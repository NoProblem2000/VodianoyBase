package com.vodianoy.pgvc.vcs;

import com.vodianoy.pgvc.common.ApiException;
import com.vodianoy.pgvc.diff.ChangeItem;
import com.vodianoy.pgvc.diff.ChangeType;
import com.vodianoy.pgvc.storage.CommitNode;
import com.vodianoy.pgvc.support.TestDatabases.Services;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static com.vodianoy.pgvc.support.TestDatabases.envId;
import static com.vodianoy.pgvc.support.TestDatabases.query;
import static com.vodianoy.pgvc.support.TestDatabases.recreate;
import static com.vodianoy.pgvc.support.TestDatabases.sql;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * v1.8.1 on a real database: a table whose partitioning changes is recreated with its rows (discard, push, rollback),
 * and linking with accepted differences reports and discards them from the point of view of the linked database.
 */
@EnabledIfEnvironmentVariable(named = "PGVC_TEST_JDBC_URL", matches = ".+")
class PartitionAndLinkDirectionIntegrationTest {

    private static final String DEV_DB = "pgvc_fix181_dev";
    private static final String STAGE_DB = "pgvc_fix181_stage";

    /** events is an ordinary table here, with a view, a serial key, and a table that references it. */
    private static final String REGULAR = """
            CREATE SCHEMA app;
            CREATE TABLE app.customers (id bigint PRIMARY KEY, name text NOT NULL);
            CREATE TABLE app.events (
                id      bigserial,
                date    date NOT NULL,
                payload text,
                PRIMARY KEY (id),
                UNIQUE (id, date)
            );
            CREATE TABLE app.notes (
                id         int PRIMARY KEY,
                event_id   bigint,
                event_date date,
                CONSTRAINT notes_event_fk FOREIGN KEY (event_id, event_date) REFERENCES app.events (id, date)
            );
            CREATE VIEW app.v_events AS SELECT id, date FROM app.events;
            INSERT INTO app.events (date, payload) SELECT date '2026-01-01' + g, 'row ' || g FROM generate_series(1, 120) g;
            INSERT INTO app.notes VALUES (1, 5, date '2026-01-06');
            """;

    /** The developer converts app.events to a table partitioned by date (keeping the rows and the dependents). */
    private static final String CONVERT_TO_PARTITIONED = """
            ALTER TABLE app.notes DROP CONSTRAINT notes_event_fk;
            DROP VIEW app.v_events;
            ALTER SEQUENCE app.events_id_seq OWNED BY NONE;
            ALTER TABLE app.events RENAME TO events_old;
            ALTER TABLE app.events_old RENAME CONSTRAINT events_pkey TO events_old_pkey;
            ALTER TABLE app.events_old RENAME CONSTRAINT events_id_date_key TO events_old_id_date_key;
            CREATE TABLE app.events (
                id      bigint NOT NULL DEFAULT nextval('app.events_id_seq'),
                date    date NOT NULL,
                payload text,
                PRIMARY KEY (id, date)
            ) PARTITION BY RANGE (date);
            CREATE TABLE app.events_2026 PARTITION OF app.events FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');
            INSERT INTO app.events SELECT * FROM app.events_old;
            DROP TABLE app.events_old;
            ALTER SEQUENCE app.events_id_seq OWNED BY app.events.id;
            -- a foreign key may reference a partitioned table from PostgreSQL 12 on
            DO $$ BEGIN
                IF current_setting('server_version_num')::int >= 120000 THEN
                    ALTER TABLE app.notes ADD CONSTRAINT notes_event_fk
                        FOREIGN KEY (event_id, event_date) REFERENCES app.events (id, date);
                END IF;
            END $$;
            CREATE VIEW app.v_events AS SELECT id, date FROM app.events;
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
        sql(DEV_DB, REGULAR);
        s = new Services(dir, DEV_DB, STAGE_DB);
        s.storage.initializeEnvironment(dev);
        s.vc.commitWorkingTree(dev, new CommitRequest("Regular events", "dev", null, null, null));
    }

    @AfterEach
    void tearDown() {
        s.close();
    }

    private static String relkind(String db) throws Exception {
        return query(db, "SELECT relkind::text FROM pg_class WHERE oid = 'app.events'::regclass");
    }

    // ------------------------------------------------------------------ partitioning

    @Test
    void discardTurnsAConvertedTableBackKeepingItsRows() throws Exception {
        sql(DEV_DB, CONVERT_TO_PARTITIONED);
        assertThat(relkind(DEV_DB)).isEqualTo("p");

        WorkingTreeStatus wt = s.vc.getWorkingTreeStatus(dev);
        assertThat(wt.diff().getUpSql()).doesNotContain("MANUAL ACTION").contains("PARTITION BY RANGE (date)");
        assertThat(wt.diff().getChanges()).anySatisfy(c -> {
            assertThat(c.getObjectName()).isEqualTo("app.events");
            assertThat(c.getDetails()).contains("recreated");
        });

        DiscardPlan plan = s.vc.discardPlan(new DiscardPlanRequest(dev, null));
        // the error of v1.8.0: PRIMARY KEY (id) on the still partitioned table
        assertThat(plan.downSql()).doesNotContain("MANUAL ACTION").contains("DROP TABLE app.events;")
                .contains("INSERT INTO app.events");
        DiscardResult result = s.vc.discardWorkingTree(new DiscardRequest(dev, null, null, plan.totalChanges(), false, null, null));
        assertThat(result.verified()).isTrue();
        assertThat(result.remainingChanges()).isZero();

        assertThat(relkind(DEV_DB)).isEqualTo("r");
        assertThat(query(DEV_DB, "SELECT count(*) FROM app.events")).isEqualTo("120");
        assertThat(query(DEV_DB, "SELECT count(*) FROM app.v_events")).isEqualTo("120");
        assertThat(query(DEV_DB, "SELECT count(*) FROM app.notes n JOIN app.events e ON e.id = n.event_id")).isEqualTo("1");
        // the serial sequence survived the DROP TABLE and continues after the copied ids
        assertThat(query(DEV_DB, "SELECT nextval('app.events_id_seq')")).isEqualTo("121");
        assertThat(query(DEV_DB, "SELECT pg_get_serial_sequence('app.events', 'id')")).isEqualTo("app.events_id_seq");
        assertThat(s.vc.getWorkingTreeStatus(dev).diff().isHasChanges()).isFalse();
    }

    @Test
    void aCommittedConversionIsPushedAndRolledBackWithTheRows() throws Exception {
        // stage gets the regular table through the history first
        s.vc.pushCommitsToEnvironment(dev, stage, null);
        sql(STAGE_DB, "INSERT INTO app.events (date, payload) SELECT date '2026-06-01' + g, 'stage ' || g FROM generate_series(1, 30) g");

        sql(DEV_DB, CONVERT_TO_PARTITIONED);
        CommitNode converted = s.vc.commitWorkingTree(dev, new CommitRequest("Partition events", "dev", null, null, null)).commit();

        PushResult push = s.vc.pushCommitsToEnvironment(dev, stage, null);
        assertThat(push.verified()).isTrue();
        assertThat(push.newHead()).isEqualTo(converted.hash());
        assertThat(relkind(STAGE_DB)).isEqualTo("p");
        // the rows of stage itself (the schema history carries no data)
        assertThat(query(STAGE_DB, "SELECT count(*) FROM app.events_2026")).isEqualTo("30");

        RollbackResult back = s.vc.rollbackToCommit(stage, new RollbackRequest(push.previousHead(), null, false, false));
        assertThat(back.verified()).isTrue();
        assertThat(back.newHead()).isEqualTo(push.previousHead());
        assertThat(relkind(STAGE_DB)).isEqualTo("r");
        assertThat(query(STAGE_DB, "SELECT count(*) FROM app.events")).isEqualTo("30");
        assertThat(query(STAGE_DB, "SELECT count(*) FROM app.v_events")).isEqualTo("30");
    }

    @Test
    void rowsThatFitNoPartitionStopTheMigrationAndNothingChanges() throws Exception {
        sql(DEV_DB, CONVERT_TO_PARTITIONED);
        CommitNode converted = s.vc.commitWorkingTree(dev, new CommitRequest("Partition events", "dev", null, null, null)).commit();
        sql(STAGE_DB, REGULAR);
        sql(STAGE_DB, "INSERT INTO app.events (date, payload) VALUES (date '2030-01-01', 'no partition for this')");
        s.storage.initializeEnvironment(stage);
        s.vc.linkEnvironmentToHistory(new LinkRequest(dev, stage, converted.parentHash(), true, false));

        assertThatThrownBy(() -> s.vc.pushCommitsToEnvironment(dev, stage, null)).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("partition");
        assertThat(relkind(STAGE_DB)).isEqualTo("r");
        assertThat(query(STAGE_DB, "SELECT count(*) FROM app.events")).isEqualTo("121");
    }

    // ------------------------------------------------------------------ link with differences

    @Test
    void linkDifferencesAreWhatTheLinkedDatabaseLacksAndDiscardAddsThem() throws Exception {
        sql(DEV_DB, "ALTER TABLE app.customers ADD COLUMN email text; ALTER TABLE app.customers ADD COLUMN meta jsonb DEFAULT '{}'::jsonb");
        CommitNode withEmail = s.vc.commitWorkingTree(dev, new CommitRequest("Email", "dev", null, null, null)).commit();
        sql(STAGE_DB, "CREATE SCHEMA app; CREATE TABLE app.customers (id bigint PRIMARY KEY, name text NOT NULL)");

        LinkPreview preview = s.vc.linkPreview(dev, stage, withEmail.hash());
        assertThat(preview.identical()).isFalse();
        assertThat(preview.targetInitialized()).isFalse();
        assertThat(preview.differences().getChanges()).extracting(ChangeItem::getChangeType).contains(ChangeType.ADDED);
        assertThat(preview.differences().getChanges()).extracting(ChangeItem::getObjectName)
                .contains("app.customers.email", "app.customers.meta", "app.events", "app.notes", "app.v_events");
        // UP = what the linked database needs to match the commit; never DROP COLUMN for what it lacks
        assertThat(preview.differences().getUpSql()).contains("ADD COLUMN email text;", "CREATE TABLE app.events")
                .doesNotContain("DROP COLUMN");

        assertThatThrownBy(() -> s.vc.linkEnvironmentToHistory(new LinkRequest(dev, stage, withEmail.hash(), false, false)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage())
                        .contains("To match the commit it would need").contains("ADDED COLUMN app.customers.email"));

        LinkResult link = s.vc.linkEnvironmentToHistory(new LinkRequest(dev, stage, withEmail.hash(), true, false));
        assertThat(link.acceptedDifferences()).extracting(ChangeItem::getObjectName).contains("app.customers.email");
        assertThat(link.acceptedDifferences()).filteredOn(c -> c.getObjectName().equals("app.customers.email"))
                .singleElement().satisfies(c -> {
                    assertThat(c.getChangeType()).isEqualTo(ChangeType.ADDED);
                    assertThat(c.getUpSql()).contains("ADD COLUMN email text;");
                });

        // working tree of the linked database: HEAD -> live (git semantics), so Discard (DOWN) adds what is missing
        DiscardPlan plan = s.vc.discardPlan(new DiscardPlanRequest(stage, null));
        assertThat(plan.downSql()).contains("ADD COLUMN email text;", "ADD COLUMN meta jsonb DEFAULT '{}'::jsonb;",
                "CREATE TABLE app.events").doesNotContain("DROP COLUMN");
        DiscardResult result = s.vc.discardWorkingTree(new DiscardRequest(stage, null, null, plan.totalChanges(), false, null, null));
        assertThat(result.verified()).isTrue();
        assertThat(query(STAGE_DB, "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'app' "
                + "AND table_name = 'customers' AND column_name IN ('email', 'meta')")).isEqualTo("2");
        assertThat(s.vc.linkPreview(dev, stage, withEmail.hash()).identical()).isTrue();
    }
}
