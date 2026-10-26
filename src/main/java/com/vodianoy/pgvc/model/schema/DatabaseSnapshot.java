package com.vodianoy.pgvc.model.schema;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Full model of the database schema at one moment ("Working Tree" when read from pg_catalog, or the state stored
 * with a commit). Covers all version-controlled user schemas; map keys are qualified names {@code schema.name}.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class DatabaseSnapshot {

    public static final int FORMAT_VERSION = 1;

    @Builder.Default
    private int formatVersion = FORMAT_VERSION;

    /** When the snapshot was taken. */
    private OffsetDateTime timestamp;

    /**
     * {@code system_identifier:database_oid} of the source database. OIDs and attnums are only comparable between
     * snapshots with the same source (used later for rename detection).
     */
    private String sourceDatabaseId;

    /** server_version_num, e.g. 160004. */
    private int serverVersion;

    /** Schemas covered by this snapshot (all user schemas unless a filter was requested), sorted. */
    @Builder.Default
    private List<String> schemas = new ArrayList<>();

    /** Tables (including partitioned tables and partitions) by {@code schema.table}. */
    @Builder.Default
    private Map<String, TableModel> tables = new LinkedHashMap<>();

    /** Views and materialized views by {@code schema.view}. */
    @Builder.Default
    private Map<String, ViewModel> views = new LinkedHashMap<>();

    /** Sequences by {@code schema.sequence}. */
    @Builder.Default
    private Map<String, SequenceModel> sequences = new LinkedHashMap<>();

    /** Map key / reference format used everywhere in the model. */
    public static String key(String schemaName, String objectName) {
        return schemaName + "." + objectName;
    }

    @JsonIgnore
    public boolean isEmpty() {
        return tables.isEmpty() && views.isEmpty() && sequences.isEmpty();
    }
}
