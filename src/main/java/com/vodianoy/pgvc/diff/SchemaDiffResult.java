package com.vodianoy.pgvc.diff;

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

    @Builder.Default
    private List<ChangeItem> changes = new ArrayList<>();

    /** Complete migration "from -> to" in a dependency-safe order. */
    private String upSql;

    /** Complete reverse migration "to -> from" in a dependency-safe order. */
    private String downSql;

    /** Dangerous or manual-attention items of the UP migration (data loss, type changes ...). */
    @Builder.Default
    private List<String> warnings = new ArrayList<>();
}
