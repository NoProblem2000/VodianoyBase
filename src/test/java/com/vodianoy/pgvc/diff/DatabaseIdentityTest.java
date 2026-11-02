package com.vodianoy.pgvc.diff;

import com.vodianoy.pgvc.model.schema.ColumnModel;
import com.vodianoy.pgvc.model.schema.DatabaseSnapshot;
import com.vodianoy.pgvc.model.schema.TableModel;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two physical copies of one cluster (pg_basebackup, one Docker image with a prepared database) share
 * system_identifier and database oid. Their OIDs / attnums must not be matched with each other: a new jsonb column of
 * one must not look like a renamed column of the other.
 */
class DatabaseIdentityTest {

    private final SchemaDiffEngine engine = new SchemaDiffEngine(() -> DiffIgnoreRules.NONE);

    private static DatabaseSnapshot docs(String id, String col2, String type2) {
        Map<String, ColumnModel> cols = new LinkedHashMap<>();
        cols.put("id", ColumnModel.builder().attnum(1).name("id").dataType("bigint").build());
        cols.put(col2, ColumnModel.builder().attnum(2).name(col2).dataType(type2).nullable(true).build());
        TableModel t = TableModel.builder().oid(16400).schemaName("public").name("docs").columns(cols).build();
        DatabaseSnapshot s = DatabaseSnapshot.builder().sourceDatabaseId(id).serverVersion(160000).schemas(List.of("public")).build();
        s.getTables().put(t.getQualifiedName(), t);
        return s;
    }

    @Test
    void sameDatabaseRules() {
        assertTrue(DatabaseSnapshot.sameDatabase("7:16384@dev", "7:16384@dev"));
        assertFalse(DatabaseSnapshot.sameDatabase("7:16384@dev", "7:16384@stage"));
        // a snapshot of v1.7.0 or older (no environment) vs a new one of the same database
        assertTrue(DatabaseSnapshot.sameDatabase("7:16384", "7:16384@dev"));
        assertFalse(DatabaseSnapshot.sameDatabase("7:16384", "8:16384@dev"));
        // pg_control_system() not readable: trusted only within one environment
        assertTrue(DatabaseSnapshot.sameDatabase("unknown:16384@dev", "unknown:16384@dev"));
        assertFalse(DatabaseSnapshot.sameDatabase("unknown:16384", "unknown:16384"));
        assertFalse(DatabaseSnapshot.sameDatabase(null, "7:16384@dev"));
    }

    @Test
    void clonesOfOneClusterAreMatchedByName() {
        DatabaseSnapshot stage = docs("7:16384@stage", "notes", "text");
        DatabaseSnapshot dev = docs("7:16384@dev", "meta", "jsonb");

        SchemaDiffResult r = engine.diff(stage, dev);

        assertTrue(r.getChanges().stream().noneMatch(c -> c.getChangeType() == ChangeType.RENAMED), r.getChanges().toString());
        assertTrue(r.getUpSql().contains("ALTER TABLE public.docs ADD COLUMN meta jsonb;"), r.getUpSql());
        assertTrue(r.getUpSql().contains("ALTER TABLE public.docs DROP COLUMN notes;"), r.getUpSql());
    }

    @Test
    void withinOneEnvironmentRenamesAreStillRecognised() {
        SchemaDiffResult r = engine.diff(docs("7:16384@dev", "notes", "text"), docs("7:16384@dev", "remarks", "text"));
        assertEquals(1, r.getChanges().size());
        assertEquals(ChangeType.RENAMED, r.getChanges().get(0).getChangeType());

        SchemaDiffResult across = engine.diffAcrossEnvironments(docs("7:16384@dev", "notes", "text"),
                docs("7:16384@dev", "remarks", "text"));
        assertTrue(across.getChanges().stream().noneMatch(c -> c.getChangeType() == ChangeType.RENAMED));
    }
}
