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

    /**
     * 1: tables, views, sequences. 2 (v1.6.0): also functions, triggers, ENUM types, extensions, comments, owners,
     * grants and tablespaces. When a version-1 snapshot is compared, the categories added in version 2 are skipped.
     */
    public static final int FORMAT_VERSION = 2;

    /** First format version with functions, triggers, enums, extensions, comments, owners, grants, tablespaces. */
    public static final int EXTENDED_OBJECTS_VERSION = 2;

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

    /** Functions and procedures by {@code schema.name(identity arguments)}. */
    @Builder.Default
    private Map<String, FunctionModel> functions = new LinkedHashMap<>();

    /** ENUM types by {@code schema.type}. */
    @Builder.Default
    private Map<String, EnumTypeModel> enumTypes = new LinkedHashMap<>();

    /** Extensions by name (database-wide, not limited to the snapshot's schemas). */
    @Builder.Default
    private Map<String, ExtensionModel> extensions = new LinkedHashMap<>();

    /** Map key / reference format used everywhere in the model. */
    public static String key(String schemaName, String objectName) {
        return schemaName + "." + objectName;
    }

    @JsonIgnore
    public boolean isEmpty() {
        // extensions are not user schema: a fresh database with an extension is still "empty"
        return tables.isEmpty() && views.isEmpty() && sequences.isEmpty() && functions.isEmpty() && enumTypes.isEmpty();
    }

    /** The snapshot contains functions, triggers, enums, extensions, comments, owners, grants and tablespaces. */
    @JsonIgnore
    public boolean hasExtendedObjects() {
        return formatVersion >= EXTENDED_OBJECTS_VERSION;
    }
}
