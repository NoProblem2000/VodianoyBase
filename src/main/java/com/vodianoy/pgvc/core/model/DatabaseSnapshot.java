package com.vodianoy.pgvc.core.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Comparator;
import java.util.List;

/**
 * Full model of a database schema at one point in time. Stored in _pgvc.commits.schema_snapshot.
 *
 * @param formatVersion    snapshot format version
 * @param sourceDatabaseId system_identifier:database_oid of the database the snapshot was taken from.
 *                         OIDs / attnums are only comparable between snapshots with the same source id.
 * @param serverVersion    server_version_num
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DatabaseSnapshot(int formatVersion, String sourceDatabaseId, Integer serverVersion,
                               List<String> schemas, List<TableDef> tables, List<IndexDef> indexes,
                               List<SequenceDef> sequences, List<ViewDef> views) {

    public DatabaseSnapshot {
        schemas = schemas == null ? List.of() : schemas.stream().sorted().toList();
        tables = tables == null ? List.of() : tables.stream().sorted(Comparator.comparing(TableDef::key)).toList();
        indexes = indexes == null ? List.of() : indexes.stream().sorted(Comparator.comparing(IndexDef::key)).toList();
        sequences = sequences == null ? List.of() : sequences.stream().sorted(Comparator.comparing(SequenceDef::key)).toList();
        views = views == null ? List.of() : views.stream().sorted(Comparator.comparing(ViewDef::key)).toList();
    }

    public static DatabaseSnapshot empty() {
        return new DatabaseSnapshot(1, null, null, List.of(), List.of(), List.of(), List.of(), List.of());
    }

    @JsonIgnore
    public boolean isEmpty() {
        return tables.isEmpty() && indexes.isEmpty() && sequences.isEmpty() && views.isEmpty()
                && schemas.stream().allMatch("public"::equals);
    }
}
