package com.vodianoy.pgvc.diff;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** Difference between two snapshots: the list of changes and the migration scripts in both directions. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SchemaDiffResult {

    private boolean hasChanges;

    /** What the comparison describes: changes already in the live database, a script to run, or history. */
    @Builder.Default
    private DiffMode mode = DiffMode.APPLIED;

    @Builder.Default
    private List<ChangeItem> changes = new ArrayList<>();

    /** Complete migration "from -> to" in a dependency-safe order. */
    private String upSql;

    /** Complete reverse migration "to -> from" in a dependency-safe order. */
    private String downSql;

    /** Dangerous or manual-attention items of the UP migration (data loss, type changes ...). */
    @Builder.Default
    private List<String> warnings = new ArrayList<>();

    /**
     * Fixes of problems found in the live schema that no change of the list belongs to (e.g. a table committed long
     * ago whose default uses a sequence of another schema).
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @Builder.Default
    private List<QuickFix> quickFixes = new ArrayList<>();

    /** {@link SchemaDiffEngine#compare} only: the UP migration statement by statement, in execution order. */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @Builder.Default
    private List<ScriptStatement> upStatements = new ArrayList<>();

    /**
     * {@link SchemaDiffEngine#compare} only: the DOWN migration statement by statement, in execution order; each
     * statement carries the id of the UP change it reverts.
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @Builder.Default
    private List<ScriptStatement> downStatements = new ArrayList<>();
}
