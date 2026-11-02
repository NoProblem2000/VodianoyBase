package com.vodianoy.pgvc.diff;

import com.vodianoy.pgvc.common.ApiException;
import com.vodianoy.pgvc.model.schema.DatabaseSnapshot;
import com.vodianoy.pgvc.model.schema.FunctionModel;
import com.vodianoy.pgvc.model.schema.TableModel;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiffIgnoreRulesTest {

    private static DiffIgnoreRules patterns(String... p) {
        return new DiffIgnoreRules(false, false, false, false, Arrays.asList(p));
    }

    @Test
    void globPatternsMatchNameOrQualifiedNameCaseInsensitively() {
        DiffIgnoreRules r = patterns("tmp_%", "bak_*", "audit.*_old", "x?y");
        assertTrue(r.excludes("public", "tmp_import"));
        assertTrue(r.excludes("public", "TMP_Import"));
        assertTrue(r.excludes("sales", "bak_orders"));
        assertTrue(r.excludes("audit", "events_old"));
        assertFalse(r.excludes("sales", "events_old"));
        assertTrue(r.excludes("public", "x1y"));
        assertFalse(r.excludes("public", "x12y"));
        // "_" is literal, not "any character" as in LIKE
        assertFalse(r.excludes("public", "tmpXimport"));
        assertFalse(r.excludes("public", "orders"));
    }

    @Test
    void regexPatternsAreSearched() {
        DiffIgnoreRules r = patterns("re:^tmp_\\d+$", "/_backup$/");
        assertTrue(r.excludes("public", "tmp_123"));
        assertFalse(r.excludes("public", "tmp_abc"));
        assertTrue(r.excludes("sales", "orders_backup"));
        assertTrue(r.excludes(null, "orders_backup"));
    }

    @Test
    void blankAndDuplicatePatternsAreDroppedAndInvalidRegexIsRejected() {
        DiffIgnoreRules r = new DiffIgnoreRules(false, false, false, false, Arrays.asList(" tmp_% ", "", null, "tmp_%"));
        assertEquals(List.of("tmp_%"), r.excludedObjectPatterns());
        assertTrue(new DiffIgnoreRules(false, false, false, false, null).excludedObjectPatterns().isEmpty());
        assertThrows(ApiException.class, () -> patterns("re:([a-z").validated());
        assertFalse(patterns("re:([a-z").excludes("public", "anything"));
    }

    @Test
    void defaultsIgnoreOwnersGrantsAndTablespacesOnly() {
        DiffIgnoreRules d = DiffIgnoreRules.DEFAULTS;
        assertTrue(d.ignoreOwnersAndGrants());
        assertTrue(d.ignoreTablespaces());
        assertFalse(d.ignoreComments());
        assertFalse(d.ignorePartitions());
        assertTrue(DiffIgnoreRules.NONE.isEmpty());
    }

    @Test
    void filterReturnsACopyAndNeverChangesTheOriginal() {
        TableModel t = TableModel.builder().oid(1).schemaName("public").name("orders").comment("c").owner("app")
                .acl(List.of("r=SELECT")).tablespace("pg_default").build();
        FunctionModel f = FunctionModel.builder().oid(2).schemaName("public").name("fn").identityArguments("")
                .kind("f").definition("x").owner("app").acl(List.of()).comment("fc").build();
        DatabaseSnapshot s = DatabaseSnapshot.builder().schemas(List.of("public")).build();
        s.getTables().put(t.getQualifiedName(), t);
        s.getFunctions().put(f.getKey(), f);

        DatabaseSnapshot filtered = SnapshotFilter.apply(s, new DiffIgnoreRules(true, true, true, false, List.of()));

        TableModel ft = filtered.getTables().get("public.orders");
        assertNull(ft.getComment());
        assertNull(ft.getOwner());
        assertNull(ft.getAcl());
        assertNull(ft.getTablespace());
        assertNull(filtered.getFunctions().get("public.fn()").getOwner());
        assertNotNull(t.getComment());
        assertEquals("app", s.getFunctions().get("public.fn()").getOwner());
    }
}
