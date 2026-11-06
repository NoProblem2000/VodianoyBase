package com.vodianoy.pgvc.gitexport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vodianoy.pgvc.common.ApiException;
import com.vodianoy.pgvc.diff.ChangeItem;
import com.vodianoy.pgvc.diff.DiffIgnoreRules;
import com.vodianoy.pgvc.diff.ObjectType;
import com.vodianoy.pgvc.diff.SchemaDiffEngine;
import com.vodianoy.pgvc.diff.SchemaDiffResult;
import com.vodianoy.pgvc.environment.Environment;
import com.vodianoy.pgvc.environment.EnvironmentManagerService;
import com.vodianoy.pgvc.introspection.PostgresSchemaIntrospector;
import com.vodianoy.pgvc.model.schema.DatabaseSnapshot;
import com.vodianoy.pgvc.model.schema.EnumTypeModel;
import com.vodianoy.pgvc.model.schema.ExtensionModel;
import com.vodianoy.pgvc.model.schema.FunctionModel;
import com.vodianoy.pgvc.model.schema.SequenceModel;
import com.vodianoy.pgvc.model.schema.TableModel;
import com.vodianoy.pgvc.model.schema.TriggerModel;
import com.vodianoy.pgvc.model.schema.ViewModel;
import com.vodianoy.pgvc.storage.CommitRecord;
import com.vodianoy.pgvc.storage.PgVcStatus;
import com.vodianoy.pgvc.storage.PgVcStorageService;
import com.vodianoy.pgvc.vcs.CommitHistory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.vodianoy.pgvc.util.SqlIdentifiers.ident;
import static com.vodianoy.pgvc.util.SqlIdentifiers.qualified;

/**
 * Exports a schema as a folder tree for code review in GitLab / GitHub: one object per file, deterministic content
 * (no timestamps; columns in attnum order, everything else sorted by name), so a re-export after a change touches
 * only the files of the changed objects.
 *
 * <pre>
 * schema/schemas.sql
 * schema/extensions/&lt;extension&gt;.sql
 * schema/enums/&lt;schema&gt;.&lt;type&gt;.sql
 * schema/sequences/&lt;schema&gt;.&lt;sequence&gt;.sql
 * schema/tables/&lt;schema&gt;.&lt;table&gt;.sql            columns, constraints, indexes, comments, grants
 * schema/views/&lt;schema&gt;.&lt;view&gt;.sql
 * schema/functions/&lt;schema&gt;.&lt;function&gt;.sql      all overloads of one name
 * schema/triggers/&lt;schema&gt;.&lt;table&gt;.&lt;trigger&gt;.sql
 * schema/reference_data/&lt;schema&gt;.&lt;table&gt;.sql    INSERT ... ON CONFLICT of every row
 * migrations/&lt;NNNN&gt;_&lt;short hash&gt;_&lt;slug&gt;.up.sql / .down.sql
 * </pre>
 *
 * The SQL of every object is produced by the diff engine (the object against an empty database), so it is exactly
 * what a migration would execute; the saved ignore rules apply (excluded objects are not exported).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GitDirectoryExporterService {

    /** Folders the exporter owns: generated files in them that no longer belong to the export are deleted. */
    static final List<String> MANAGED_DIRS = List.of("schema/extensions", "schema/enums", "schema/sequences",
            "schema/tables", "schema/views", "schema/functions", "schema/triggers", "schema/reference_data",
            "migrations");

    private final EnvironmentManagerService environments;
    private final PgVcStorageService storage;
    private final PostgresSchemaIntrospector introspector;
    private final SchemaDiffEngine diffEngine;
    private final CommitHistory history;
    private final ObjectMapper objectMapper;

    // ------------------------------------------------------------------ entry points

    /** Renders the export of a commit (blank: HEAD, or the live schema when there are no commits). */
    public GitExport render(String envId, String commitHash, boolean includeMigrations) {
        Environment env = requireEnvironment(envId);
        PgVcStatus status = storage.getStatus(envId);
        if (!status.reachable()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "env.unreachable", envId, status.error());
        }
        String hash = commitHash == null || commitHash.isBlank() ? (status.initialized() ? status.head() : null)
                : commitHash.strip();
        DatabaseSnapshot snapshot;
        List<CommitRecord> chain = List.of();
        if (hash != null) {
            CommitRecord commit = storage.requireCommit(envId, hash);
            snapshot = parse(commit.getSchemaSnapshotJson());
            chain = history.range(envId, null, commit.getHash());
        } else {
            snapshot = introspector.introspect(envId);
        }
        SortedMap<String, String> files = new TreeMap<>(renderSchema(snapshot));
        if (includeMigrations) {
            files.putAll(renderMigrations(chain));
        }
        String zipName = "pgvc-git-export_" + fileName(env.getId()) + "_" + (hash == null ? "live" : hash.substring(0, 7)) + ".zip";
        log.info("Git export of {} @ {}: {} files", envId, hash == null ? "live" : hash.substring(0, 7), files.size());
        return new GitExport(envId, hash, zipName, files);
    }

    /** The export as a .zip (entries sorted, fixed timestamps: the same schema gives the same bytes). */
    public byte[] zip(GitExport export) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> f : export.files().entrySet()) {
                ZipEntry entry = new ZipEntry(f.getKey());
                entry.setTime(315532800000L); // 1980-01-01, the earliest zip timestamp
                zip.putNextEntry(entry);
                zip.write(f.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /**
     * Writes the export into an existing folder (usually a Git working copy): new and changed files are written,
     * files with the same content are left untouched, and generated {@code .sql} files of objects that no longer
     * exist are deleted from the folders the exporter owns. Nothing outside {@code schema/} and {@code migrations/}
     * is touched; nothing is committed.
     */
    public GitExportResult writeTo(GitExport export, String targetPath) {
        Path root = requireFolder(targetPath);
        List<String> written = new ArrayList<>();
        int unchanged = 0;
        try {
            for (Map.Entry<String, String> f : export.files().entrySet()) {
                Path file = root.resolve(f.getKey()).normalize();
                if (!file.startsWith(root)) {
                    throw ApiException.badRequest("gitExport.badPath", f.getKey());
                }
                byte[] content = f.getValue().getBytes(StandardCharsets.UTF_8);
                if (Files.isRegularFile(file) && java.util.Arrays.equals(Files.readAllBytes(file), content)) {
                    unchanged++;
                    continue;
                }
                Files.createDirectories(file.getParent());
                Files.write(file, content);
                written.add(f.getKey());
            }
            List<String> deleted = new ArrayList<>();
            for (String dir : MANAGED_DIRS) {
                Path folder = root.resolve(dir);
                if (!Files.isDirectory(folder)) {
                    continue;
                }
                try (Stream<Path> listing = Files.list(folder)) {
                    for (Path p : listing.sorted().toList()) {
                        String rel = root.relativize(p).toString().replace('\\', '/');
                        if (Files.isRegularFile(p) && p.getFileName().toString().endsWith(".sql")
                                && !export.files().containsKey(rel)) {
                            Files.delete(p);
                            deleted.add(rel);
                        }
                    }
                }
            }
            log.info("Git export of {} written to {}: {} written, {} unchanged, {} deleted", export.envId(), root,
                    written.size(), unchanged, deleted.size());
            return new GitExportResult(export.envId(), export.commitHash(), root.toString(), written, unchanged, deleted,
                    export.files().size());
        } catch (IOException e) {
            throw ApiException.badRequest("gitExport.writeFailed", root.toString(), e.getMessage());
        }
    }

    // ------------------------------------------------------------------ schema

    /** One file per object of the snapshot. */
    SortedMap<String, String> renderSchema(DatabaseSnapshot snapshot) {
        DiffIgnoreRules rules = diffEngine.defaultRules();
        SortedMap<String, String> files = new TreeMap<>();
        Set<String> usedNames = new HashSet<>();

        List<String> schemas = snapshot.getSchemas().stream().filter(s -> !"public".equals(s)).sorted().toList();
        if (!schemas.isEmpty()) {
            files.put("schema/schemas.sql", header("schemas", null)
                    + schemas.stream().map(s -> "CREATE SCHEMA IF NOT EXISTS " + ident(s) + ";").collect(Collectors.joining("\n"))
                    + "\n");
        }
        for (ExtensionModel e : new TreeMap<>(snapshot.getExtensions()).values()) {
            DatabaseSnapshot one = single(snapshot);
            one.getExtensions().put(e.getName(), e);
            put(files, usedNames, "schema/extensions/", e.getName(), header("extension", e.getName()), sqlOf(one, rules, null));
        }
        for (EnumTypeModel e : new TreeMap<>(snapshot.getEnumTypes()).values()) {
            DatabaseSnapshot one = single(snapshot);
            one.getEnumTypes().put(e.getQualifiedName(), e);
            put(files, usedNames, "schema/enums/", e.getQualifiedName(), header("enum type", e.getQualifiedName()),
                    sqlOf(one, rules, null));
        }
        for (SequenceModel q : new TreeMap<>(snapshot.getSequences()).values()) {
            DatabaseSnapshot one = single(snapshot);
            one.getSequences().put(q.getQualifiedName(), q);
            put(files, usedNames, "schema/sequences/", q.getQualifiedName(), header("sequence", q.getQualifiedName()),
                    sqlOf(one, rules, null));
        }
        for (TableModel t : new TreeMap<>(snapshot.getTables()).values()) {
            if (rules.excludes(t.getSchemaName(), t.getName()) || (rules.ignorePartitions() && t.isPartition())) {
                continue;
            }
            // the table without its triggers and rows: those get files of their own
            DatabaseSnapshot one = single(snapshot);
            one.getTables().put(t.getQualifiedName(), t.toBuilder().triggers(new LinkedHashMap<>()).referenceData(null).build());
            put(files, usedNames, "schema/tables/", t.getQualifiedName(),
                    header(t.isPartition() ? "partition" : "table", t.getQualifiedName()), sqlOf(one, rules, null));

            String tq = qualified(t.getSchemaName(), t.getName());
            for (TriggerModel tr : new TreeMap<>(t.getTriggers()).values()) {
                if (rules.excludes(t.getSchemaName(), tr.getName())) {
                    continue;
                }
                put(files, usedNames, "schema/triggers/", t.getQualifiedName() + "." + tr.getName(),
                        header("trigger", t.getQualifiedName() + "." + tr.getName()), triggerSql(tq, tr));
            }
            if (t.getReferenceData() != null && !t.getReferenceData().isTruncated()) {
                DatabaseSnapshot data = single(snapshot);
                data.getTables().put(t.getQualifiedName(), t.toBuilder().triggers(new LinkedHashMap<>()).build());
                String rows = sqlOf(data, rules, ObjectType.DATA);
                put(files, usedNames, "schema/reference_data/", t.getQualifiedName(),
                        header("reference data", t.getQualifiedName() + " (" + t.getReferenceData().getRowCount() + " rows)"),
                        rows.isBlank() ? "-- (no rows)" : rows);
            }
        }
        for (ViewModel v : new TreeMap<>(snapshot.getViews()).values()) {
            DatabaseSnapshot one = single(snapshot);
            one.getViews().put(v.getQualifiedName(), v);
            put(files, usedNames, "schema/views/", v.getQualifiedName(),
                    header(v.isMaterialized() ? "materialized view" : "view", v.getQualifiedName()), sqlOf(one, rules, null));
        }
        // all overloads of one function name in one file, ordered by their arguments
        Map<String, List<FunctionModel>> byName = new TreeMap<>();
        for (FunctionModel f : new TreeMap<>(snapshot.getFunctions()).values()) {
            byName.computeIfAbsent(DatabaseSnapshot.key(f.getSchemaName(), f.getName()), k -> new ArrayList<>()).add(f);
        }
        for (Map.Entry<String, List<FunctionModel>> e : byName.entrySet()) {
            List<String> parts = new ArrayList<>();
            for (FunctionModel f : e.getValue()) {
                DatabaseSnapshot one = single(snapshot);
                one.getFunctions().put(f.getKey(), f);
                String sql = sqlOf(one, rules, null);
                if (!sql.isBlank()) {
                    parts.add(sql);
                }
            }
            put(files, usedNames, "schema/functions/", e.getKey(),
                    header(e.getValue().get(0).isProcedure() ? "procedure" : "function", e.getKey()), String.join("\n\n", parts));
        }
        return files;
    }

    /** An empty snapshot of the same database (same schemas, so no CREATE SCHEMA is generated). */
    private static DatabaseSnapshot single(DatabaseSnapshot source) {
        return DatabaseSnapshot.builder().formatVersion(source.getFormatVersion()).sourceDatabaseId("export")
                .serverVersion(source.getServerVersion()).schemas(new ArrayList<>(source.getSchemas())).build();
    }

    /**
     * SQL that creates the objects of {@code one} in an empty database.
     *
     * @param only only changes of this kind (null: all)
     */
    private String sqlOf(DatabaseSnapshot one, DiffIgnoreRules rules, ObjectType only) {
        DatabaseSnapshot empty = single(one);
        SchemaDiffResult diff = diffEngine.compare(empty, one, true, rules);
        return diff.getChanges().stream()
                .filter(c -> only == null ? c.getObjectType() != ObjectType.DATA : c.getObjectType() == only)
                .map(ChangeItem::getUpSql)
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining("\n"));
    }

    static String triggerSql(String tq, TriggerModel tr) {
        String def = tr.getDefinition() == null ? "" : tr.getDefinition().strip();
        if (def.endsWith(";")) {
            def = def.substring(0, def.length() - 1).strip();
        }
        StringBuilder sb = new StringBuilder(def.replace(" EXECUTE PROCEDURE ", " EXECUTE FUNCTION ")).append(';');
        String state = switch (tr.getEnabled() == null ? "O" : tr.getEnabled()) {
            case "D" -> "DISABLE TRIGGER ";
            case "R" -> "ENABLE REPLICA TRIGGER ";
            case "A" -> "ENABLE ALWAYS TRIGGER ";
            default -> null;
        };
        if (state != null) {
            sb.append("\nALTER TABLE ").append(tq).append(' ').append(state).append(ident(tr.getName())).append(';');
        }
        return sb.toString();
    }

    private static String header(String kind, String name) {
        return "-- " + kind + (name == null ? "" : ": " + name) + "\n-- Generated by PgGitVisualizer. Do not edit by hand:"
                + " change the database and export again.\n\n";
    }

    /** Adds a file unless the object produced no SQL (excluded by the ignore rules). */
    private static void put(SortedMap<String, String> files, Set<String> usedNames, String dir, String name,
                            String header, String sql) {
        if (sql == null || sql.isBlank()) {
            return;
        }
        String base = dir + fileName(name);
        String path = base + ".sql";
        for (int i = 2; usedNames.contains(path.toLowerCase(Locale.ROOT)); i++) {
            path = base + "_" + i + ".sql";
        }
        usedNames.add(path.toLowerCase(Locale.ROOT));
        files.put(path, header + sql.strip() + "\n");
    }

    // ------------------------------------------------------------------ migrations

    /** {@code migrations/0001_<short hash>_<slug>.up.sql} and {@code .down.sql} of every commit, oldest first. */
    static SortedMap<String, String> renderMigrations(List<CommitRecord> chain) {
        SortedMap<String, String> files = new TreeMap<>();
        for (int i = 0; i < chain.size(); i++) {
            CommitRecord c = chain.get(i);
            String base = String.format("migrations/%04d_%s_%s", i + 1, c.getHash().substring(0, 7), slug(c.getMessage()));
            String head = "-- Commit:  " + c.getHash() + "\n-- Parent:  " + (c.getParentHash() == null ? "-" : c.getParentHash())
                    + "\n-- Author:  " + c.getAuthor() + "\n-- Date:    " + c.getCreatedAt()
                    + "\n-- Message: " + c.getMessage().replace("\n", "\n--          ")
                    + (c.getDescription() == null || c.getDescription().isBlank() ? ""
                    : "\n--\n-- " + c.getDescription().strip().replace("\n", "\n-- ")) + "\n\n";
            files.put(base + ".up.sql", head + nullToEmpty(c.getUpSql()).strip() + "\n");
            files.put(base + ".down.sql", head + nullToEmpty(c.getDownSql()).strip() + "\n");
        }
        return files;
    }

    /** Lower-case letters and digits (any alphabet) joined by '-', at most 50 characters. */
    static String slug(String message) {
        String firstLine = message == null ? "" : message.strip().split("\\R", 2)[0];
        StringBuilder sb = new StringBuilder();
        boolean dash = false;
        for (int cp : firstLine.toLowerCase(Locale.ROOT).codePoints().toArray()) {
            if (Character.isLetterOrDigit(cp)) {
                sb.appendCodePoint(cp);
                dash = false;
            } else if (!dash && !sb.isEmpty()) {
                sb.append('-');
                dash = true;
            }
            if (sb.length() >= 50) {
                break;
            }
        }
        String s = sb.toString().replaceAll("-+$", "");
        return s.isEmpty() ? "commit" : s;
    }

    /** Letters, digits, '.', '-', '_' (any alphabet); everything else becomes '_'. */
    static String fileName(String name) {
        StringBuilder sb = new StringBuilder();
        for (int cp : name.codePoints().toArray()) {
            sb.appendCodePoint(Character.isLetterOrDigit(cp) || cp == '.' || cp == '-' || cp == '_' ? cp : '_');
        }
        String s = sb.toString();
        return s.startsWith(".") ? "_" + s.substring(1) : s;
    }

    // ------------------------------------------------------------------ helpers

    private Environment requireEnvironment(String envId) {
        if (envId == null || envId.isBlank()) {
            throw ApiException.badRequest("env.idRequired");
        }
        return environments.getEnvironment(envId);
    }

    private static Path requireFolder(String targetPath) {
        if (targetPath == null || targetPath.isBlank()) {
            throw ApiException.badRequest("gitExport.pathRequired");
        }
        Path p = Path.of(targetPath.strip());
        if (!p.isAbsolute()) {
            throw ApiException.badRequest("gitExport.pathNotAbsolute", targetPath);
        }
        Path root = p.normalize();
        if (!Files.isDirectory(root)) {
            throw ApiException.badRequest("gitExport.notAFolder", root.toString());
        }
        if (root.getParent() == null) {
            throw ApiException.badRequest("gitExport.rootFolder", root.toString());
        }
        return root;
    }

    private DatabaseSnapshot parse(String json) {
        try {
            return objectMapper.readValue(json, DatabaseSnapshot.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read schema snapshot: " + e.getMessage(), e);
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
