package com.vodianoy.pgvc.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vodianoy.pgvc.common.ApiException;
import com.vodianoy.pgvc.diff.ChangeItem;
import com.vodianoy.pgvc.diff.ChangeType;
import com.vodianoy.pgvc.diff.ObjectType;
import com.vodianoy.pgvc.storage.CommitNode;
import com.vodianoy.pgvc.support.TestDatabases;
import com.vodianoy.pgvc.support.TestDatabases.Services;
import com.vodianoy.pgvc.vcs.CommitRequest;
import com.vodianoy.pgvc.vcs.CommitResult;
import com.vodianoy.pgvc.vcs.ExecuteMigrationRequest;
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
 * Dev exports a reference snapshot; an isolated "customer" database (other OIDs, its own extra objects) is compared
 * with it and brought in line with one commit.
 */
@EnabledIfEnvironmentVariable(named = "PGVC_TEST_JDBC_URL", matches = ".+")
class SnapshotSyncIntegrationTest {

    private static final String DEV_DB = "pgvc_step9_snap_dev";
    private static final String CUSTOMER_DB = "pgvc_step9_snap_customer";

    @TempDir
    Path dir;

    private Services s;
    private final String dev = envId(DEV_DB);
    private final String customer = envId(CUSTOMER_DB);
    private final ObjectMapper json = TestDatabases.objectMapper();

    @BeforeEach
    void setUp() throws Exception {
        recreate(DEV_DB);
        recreate(CUSTOMER_DB);
        sql(DEV_DB, TestDatabases.fixture("rich-schema.sql"));
        // the customer got the same initial schema long ago (different OIDs) and added its own objects
        sql(CUSTOMER_DB, "CREATE TABLE public.customer_only (id int PRIMARY KEY, note text)");
        sql(CUSTOMER_DB, TestDatabases.fixture("rich-schema.sql"));
        sql(CUSTOMER_DB, "ALTER TABLE sales.customers ADD COLUMN local_code text");
        s = new Services(dir, DEV_DB, CUSTOMER_DB);
        s.storage.initializeEnvironment(dev);
    }

    @AfterEach
    void tearDown() {
        s.close();
    }

    private CommitNode execute(String env, String message, String sql) {
        return s.vc.executeAndCommitSql(env, new ExecuteMigrationRequest(message, "dev", sql, null, false)).commit();
    }

    /** Export, write the file, read it back (as the customer would). */
    private SnapshotBundleDto roundTrip(SnapshotExport export) throws Exception {
        return json.readValue(json.writeValueAsBytes(export.bundle()), SnapshotBundleDto.class);
    }

    @Test
    void customerDatabaseIsBroughtInLineWithTheReferenceSnapshot() throws Exception {
        CommitNode base = s.vc.commitWorkingTree(dev, new CommitRequest("Baseline", "dev", null, null, null)).commit();
        execute(dev, "Notes", "CREATE TABLE sales.notes (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,"
                + " customer_id bigint REFERENCES sales.customers(id), body text NOT NULL);");
        execute(dev, "Notes index", "CREATE INDEX notes_customer_idx ON sales.notes (customer_id);");
        CommitNode head = execute(dev, "February partition", "CREATE TABLE billing.events_2026_02 PARTITION OF"
                + " billing.events FOR VALUES FROM ('2026-02-01') TO ('2026-03-01');");

        // ---- export HEAD of dev
        SnapshotExport export = s.snapshots.export(dev, null);
        assertThat(export.fileName()).isEqualTo("snapshot_" + dev + "_" + head.hash().substring(0, 7) + ".pgsnap.json");
        SnapshotBundleDto bundle = roundTrip(export);
        assertThat(bundle.getFormatVersion()).isEqualTo("1.0");
        assertThat(bundle.getSourceCommitHash()).isEqualTo(head.hash());
        assertThat(bundle.getSourceCommitMessage()).isEqualTo("February partition");
        assertThat(bundle.getSourceEnvironmentName()).isEqualTo(DEV_DB);
        assertThat(bundle.getSourceBranch()).isEqualTo(dev);
        assertThat(bundle.getExportedAt()).isNotNull();

        // ---- compare on the customer side (no _pgvc there yet)
        SnapshotComparisonResponse cmp = s.snapshots.compare(new SnapshotCompareRequest(customer, bundle));
        assertThat(cmp.identical()).isFalse();
        assertThat(cmp.source().sourceCommitHash()).isEqualTo(head.hash());
        assertThat(cmp.targetHead()).isNull();
        assertThat(cmp.targetHasUncommittedChanges()).isTrue();
        // the shared fixture objects match by name despite different OIDs: only the real differences are listed
        assertThat(cmp.missingOrModifiedItems()).extracting(ChangeItem::getObjectName)
                .contains("sales.notes", "sales.notes_customer_idx", "billing.events_2026_02")
                .doesNotContain("sales.orders", "public.customer_only");
        assertThat(cmp.missingOrModifiedItems()).allMatch(c -> !c.isExtraInTargetDb());
        assertThat(cmp.destructiveItems()).extracting(ChangeItem::getObjectName)
                .contains("public.customer_only", "sales.customers.local_code");
        assertThat(cmp.destructiveItems()).allMatch(c -> c.getChangeType() == ChangeType.DELETED && c.isExtraInTargetDb());
        // the default script adds what is missing and drops nothing
        assertThat(cmp.upSql()).contains("CREATE TABLE sales.notes").contains("PARTITION OF billing.events")
                .doesNotContain("customer_only").doesNotContain("local_code");
        assertThat(cmp.downSql()).contains("DROP TABLE sales.notes");
        assertThat(cmp.upStatements()).isNotEmpty();

        // ---- apply the default selection
        CommitResult applied = s.snapshots.apply(new SnapshotApplyRequest(customer, cmp.upSql(), cmp.downSql(),
                "ops", null, head.hash()));
        assertThat(applied.commit().message()).isEqualTo("Sync with external snapshot [" + head.hash().substring(0, 7) + "]");
        assertThat(s.storage.getStatus(customer).initialized()).isTrue();
        assertThat(s.storage.getHead(customer)).contains(applied.commit().hash());
        assertThat(s.storage.readAuditLog(customer)).isEmpty();
        assertThat(query(CUSTOMER_DB, "SELECT count(*) FROM pg_class WHERE relname = 'customer_only'")).isEqualTo("1");
        // the commit records the real resulting schema: nothing uncommitted afterwards
        assertThat(s.vc.getWorkingTreeStatus(customer).diff().isHasChanges()).isFalse();

        // ---- compare again: only the customer's own objects remain, and they are not selected
        SnapshotComparisonResponse again = s.snapshots.compare(new SnapshotCompareRequest(customer, bundle));
        assertThat(again.missingOrModifiedItems()).isEmpty();
        assertThat(again.destructiveItems()).extracting(ChangeItem::getObjectName)
                .containsExactlyInAnyOrder("public.customer_only", "sales.customers.local_code");
        assertThat(again.upSql()).isEmpty();

        // ---- an older commit can be exported too
        SnapshotExport old = s.snapshots.export(dev, base.hash());
        assertThat(old.bundle().getSourceCommitHash()).isEqualTo(base.hash());
        assertThat(old.bundle().getSchemaSnapshot().getTables()).doesNotContainKey("sales.notes");
    }

    @Test
    void liveExportAndValidation() throws Exception {
        // no commits on the customer: its live schema is exported
        SnapshotExport live = s.snapshots.export(customer, null);
        assertThat(live.fileName()).isEqualTo("snapshot_" + customer + "_live.pgsnap.json");
        assertThat(live.bundle().getSourceCommitHash()).isNull();
        assertThat(live.bundle().getSchemaSnapshot().getTables()).containsKey("public.customer_only");

        // comparing a database with its own live snapshot: identical
        SnapshotComparisonResponse self = s.snapshots.compare(new SnapshotCompareRequest(customer, roundTrip(live)));
        assertThat(self.identical()).isTrue();
        assertThat(self.missingOrModifiedItems()).isEmpty();
        assertThat(self.destructiveItems()).isEmpty();

        SnapshotBundleDto wrongVersion = roundTrip(live);
        wrongVersion.setFormatVersion("2.0");
        assertThatThrownBy(() -> s.snapshots.compare(new SnapshotCompareRequest(customer, wrongVersion)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).contains("Unsupported"));
        SnapshotBundleDto empty = SnapshotBundleDto.builder().build();
        assertThatThrownBy(() -> s.snapshots.compare(new SnapshotCompareRequest(customer, empty)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).contains("schemaSnapshot"));
        // a failing script changes nothing
        assertThatThrownBy(() -> s.snapshots.apply(new SnapshotApplyRequest(customer,
                "CREATE TABLE public.ok (id int); CREATE TABLE public.customer_only (id int);", null, "ops", "x", null)))
                .isInstanceOf(RuntimeException.class);
        assertThat(query(CUSTOMER_DB, "SELECT count(*) FROM pg_class WHERE relname = 'ok'")).isEqualTo("0");
        assertThat(ChangeType.DELETED).isNotNull();
        assertThat(ObjectType.TABLE).isNotNull();
    }
}
