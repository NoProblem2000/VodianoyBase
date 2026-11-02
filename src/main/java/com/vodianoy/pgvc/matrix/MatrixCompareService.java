package com.vodianoy.pgvc.matrix;

import com.vodianoy.pgvc.common.ApiException;
import com.vodianoy.pgvc.diff.ChangeItem;
import com.vodianoy.pgvc.diff.ChangeType;
import com.vodianoy.pgvc.diff.DiffIgnoreRules;
import com.vodianoy.pgvc.diff.ObjectType;
import com.vodianoy.pgvc.diff.SchemaDiffEngine;
import com.vodianoy.pgvc.diff.SchemaDiffResult;
import com.vodianoy.pgvc.diff.SnapshotFilter;
import com.vodianoy.pgvc.environment.Environment;
import com.vodianoy.pgvc.environment.EnvironmentManagerService;
import com.vodianoy.pgvc.introspection.PostgresSchemaIntrospector;
import com.vodianoy.pgvc.model.schema.DatabaseSnapshot;
import com.vodianoy.pgvc.model.schema.EnumTypeModel;
import com.vodianoy.pgvc.model.schema.FunctionModel;
import com.vodianoy.pgvc.model.schema.SequenceModel;
import com.vodianoy.pgvc.model.schema.TableModel;
import com.vodianoy.pgvc.model.schema.ViewModel;
import com.vodianoy.pgvc.snapshot.SnapshotBundleDto;
import com.vodianoy.pgvc.snapshot.SnapshotSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * N-database matrix: every database (live environment or uploaded snapshot) is compared with one baseline, by object
 * names only (like a schema compare between unrelated databases). For each top-level object the matrix tells, per
 * database, whether it is identical to the baseline, differs, is missing or exists only there, together with the SQL
 * that brings it to the state of the baseline.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatrixCompareService {

    static final String ENV_PREFIX = "env:";
    static final String SNAPSHOT_PREFIX = "snapshot:";

    /** Row order: by kind, then by name. */
    private static final List<ObjectType> TYPE_ORDER = List.of(ObjectType.SCHEMA, ObjectType.EXTENSION, ObjectType.TYPE,
            ObjectType.TABLE, ObjectType.PARTITION, ObjectType.VIEW, ObjectType.SEQUENCE, ObjectType.FUNCTION,
            ObjectType.PROCEDURE);

    private final EnvironmentManagerService environments;
    private final PostgresSchemaIntrospector introspector;
    private final SchemaDiffEngine diffEngine;

    /** One column while it is being loaded. */
    private record Source(String id, String name, String kind, String description, DatabaseSnapshot snapshot,
                          String error) {
    }

    public MatrixCompareResponse compare(MatrixCompareRequest request) {
        List<String> envIds = request == null || request.envIds() == null ? List.of()
                : new ArrayList<>(new LinkedHashSet<>(request.envIds()));
        List<MatrixSnapshotUpload> uploads = request == null || request.snapshots() == null ? List.of() : request.snapshots();
        if (envIds.size() + uploads.size() < 2) {
            throw ApiException.badRequest("matrix.tooFew");
        }
        DiffIgnoreRules rules = request.ignoreRules() == null ? diffEngine.defaultRules() : request.ignoreRules().validated();

        List<Source> sources = new ArrayList<>(loadEnvironments(envIds));
        for (int i = 0; i < uploads.size(); i++) {
            MatrixSnapshotUpload upload = uploads.get(i);
            SnapshotBundleDto bundle = SnapshotSyncService.validate(upload == null ? null : upload.bundle());
            String name = upload.name() == null || upload.name().isBlank() ? defaultName(bundle, i) : upload.name().strip();
            sources.add(new Source(SNAPSHOT_PREFIX + i, name, "SNAPSHOT", describe(bundle), bundle.getSchemaSnapshot(), null));
        }

        String baselineId = request.baseline() == null || request.baseline().isBlank()
                ? sources.get(0).id() : request.baseline().strip();
        Source baseline = sources.stream().filter(s -> s.id().equals(baselineId)).findFirst()
                .orElseThrow(() -> ApiException.badRequest("matrix.unknownBaseline", baselineId));
        if (baseline.snapshot() == null) {
            throw ApiException.conflict("matrix.baselineUnavailable", baseline.name(), String.valueOf(baseline.error()));
        }

        // objects of the baseline (after the ignore rules), by row key
        Map<String, RowKey> rowKeys = new TreeMap<>();
        Set<String> inBaseline = new LinkedHashSet<>();
        for (RowKey key : roots(SnapshotFilter.apply(baseline.snapshot(), rules,
                !baseline.snapshot().hasExtendedObjects()))) {
            rowKeys.put(key.sortKey(), key);
            inBaseline.add(key.sortKey());
        }

        // every other database vs the baseline
        Map<String, Map<String, MatrixCellDto>> cellsBySource = new LinkedHashMap<>();
        for (Source source : sources) {
            if (source == baseline || source.snapshot() == null) {
                continue;
            }
            SchemaDiffResult diff = diffEngine.compare(source.snapshot(), baseline.snapshot(), true, rules);
            Map<String, List<ChangeItem>> byRoot = new LinkedHashMap<>();
            for (ChangeItem item : diff.getChanges()) {
                RowKey key = RowKey.of(item);
                rowKeys.putIfAbsent(key.sortKey(), key);
                byRoot.computeIfAbsent(key.sortKey(), k -> new ArrayList<>()).add(item);
            }
            Map<String, MatrixCellDto> cells = new LinkedHashMap<>();
            byRoot.forEach((key, items) -> cells.put(key, cell(rowKeys.get(key), items)));
            cellsBySource.put(source.id(), cells);
        }

        List<MatrixRowDto> rows = new ArrayList<>();
        Map<String, int[]> counts = new LinkedHashMap<>();
        sources.forEach(s -> counts.put(s.id(), new int[4]));
        List<RowKey> ordered = new ArrayList<>(rowKeys.values());
        ordered.sort(Comparator.comparingInt((RowKey k) -> typeOrder(k.type())).thenComparing(RowKey::name));
        for (RowKey key : ordered) {
            boolean baselineHas = inBaseline.contains(key.sortKey());
            Map<String, MatrixCellDto> cells = new LinkedHashMap<>();
            boolean differs = false;
            for (Source source : sources) {
                MatrixCellDto cell;
                if (source.snapshot() == null) {
                    cell = MatrixCellDto.of(MatrixCellStatus.UNAVAILABLE);
                } else if (source == baseline) {
                    cell = MatrixCellDto.of(baselineHas ? MatrixCellStatus.IDENTICAL : MatrixCellStatus.ABSENT);
                } else {
                    MatrixCellDto computed = cellsBySource.getOrDefault(source.id(), Map.of()).get(key.sortKey());
                    cell = computed != null ? computed
                            : MatrixCellDto.of(baselineHas ? MatrixCellStatus.IDENTICAL : MatrixCellStatus.ABSENT);
                    int[] c = counts.get(source.id());
                    switch (cell.status()) {
                        case IDENTICAL -> c[0]++;
                        case MODIFIED -> c[1]++;
                        case MISSING -> c[2]++;
                        case EXTRA_ONLY_HERE -> c[3]++;
                        default -> {
                        }
                    }
                    differs |= cell.status() == MatrixCellStatus.MODIFIED || cell.status() == MatrixCellStatus.MISSING
                            || cell.status() == MatrixCellStatus.EXTRA_ONLY_HERE;
                }
                cells.put(source.id(), cell);
            }
            rows.add(new MatrixRowDto(key.type(), key.name(), differs, cells));
        }

        List<MatrixDatabaseDto> databases = new ArrayList<>();
        for (Source s : sources) {
            int[] c = counts.get(s.id());
            if (s == baseline) {
                c[0] = inBaseline.size();
            }
            databases.add(new MatrixDatabaseDto(s.id(), s.name(), s.kind(), s == baseline, s.description(), s.error(),
                    s.snapshot() == null ? 0 : s.snapshot().getServerVersion(), c[0], c[1], c[2], c[3]));
        }
        log.info("Matrix of {} databases (baseline {}): {} objects, {} differ", sources.size(), baseline.name(),
                rows.size(), rows.stream().filter(MatrixRowDto::differs).count());
        return new MatrixCompareResponse(baseline.id(), databases, rows, rules);
    }

    // ------------------------------------------------------------------ loading

    private List<Source> loadEnvironments(List<String> envIds) {
        List<Environment> envs = envIds.stream().map(environments::getEnvironment).toList();
        if (envs.isEmpty()) {
            return List.of();
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(envs.size(), 8));
        try {
            List<CompletableFuture<Source>> futures = envs.stream().map(env -> CompletableFuture.supplyAsync(() -> {
                try {
                    return new Source(ENV_PREFIX + env.getId(), env.getName(), "ENVIRONMENT", null,
                            introspector.introspect(env.getId()), null);
                } catch (RuntimeException e) {
                    log.warn("Matrix: cannot read {}: {}", env.getId(), e.getMessage());
                    return new Source(ENV_PREFIX + env.getId(), env.getName(), "ENVIRONMENT", null, null,
                            rootMessage(e));
                }
            }, pool)).toList();
            return futures.stream().map(CompletableFuture::join).toList();
        } finally {
            pool.shutdown();
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    private static String defaultName(SnapshotBundleDto bundle, int index) {
        String env = bundle.getSourceEnvironmentName();
        String hash = bundle.getSourceCommitHash();
        String base = env == null || env.isBlank() ? "snapshot " + (index + 1) : env;
        return hash == null || hash.isBlank() ? base + " (snapshot)" : base + " @" + hash.substring(0, Math.min(7, hash.length()));
    }

    private static String describe(SnapshotBundleDto bundle) {
        List<String> parts = new ArrayList<>();
        if (bundle.getSourceEnvironmentName() != null) {
            parts.add(bundle.getSourceEnvironmentName());
        }
        if (bundle.getSourceCommitHash() != null) {
            parts.add(bundle.getSourceCommitHash().substring(0, Math.min(7, bundle.getSourceCommitHash().length())));
        }
        if (bundle.getExportedAt() != null) {
            parts.add(bundle.getExportedAt().toString());
        }
        return String.join(" · ", parts);
    }

    // ------------------------------------------------------------------ rows and cells

    /** A row: top-level object kind and qualified name. */
    record RowKey(ObjectType type, String name) {
        String sortKey() {
            return type + "|" + name;
        }

        static RowKey of(ChangeItem item) {
            ObjectType type = item.getRootType() == null ? item.getObjectType() : item.getRootType();
            String name = item.getRootName() == null ? item.getObjectName() : item.getRootName();
            return new RowKey(type, name);
        }
    }

    private static int typeOrder(ObjectType type) {
        int i = TYPE_ORDER.indexOf(type);
        return i < 0 ? TYPE_ORDER.size() : i;
    }

    /** Top-level objects of a snapshot (the same names the diff engine uses as change roots). */
    static List<RowKey> roots(DatabaseSnapshot s) {
        List<RowKey> keys = new ArrayList<>();
        for (String schema : s.getSchemas()) {
            if (!"public".equals(schema)) {
                keys.add(new RowKey(ObjectType.SCHEMA, schema));
            }
        }
        s.getExtensions().keySet().forEach(name -> keys.add(new RowKey(ObjectType.EXTENSION, name)));
        for (EnumTypeModel e : s.getEnumTypes().values()) {
            keys.add(new RowKey(ObjectType.TYPE, e.getQualifiedName()));
        }
        for (TableModel t : s.getTables().values()) {
            keys.add(new RowKey(t.isPartition() ? ObjectType.PARTITION : ObjectType.TABLE, t.getQualifiedName()));
        }
        for (ViewModel v : s.getViews().values()) {
            keys.add(new RowKey(ObjectType.VIEW, v.getQualifiedName()));
        }
        for (SequenceModel q : s.getSequences().values()) {
            keys.add(new RowKey(ObjectType.SEQUENCE, q.getQualifiedName()));
        }
        for (FunctionModel f : s.getFunctions().values()) {
            keys.add(new RowKey(f.isProcedure() ? ObjectType.PROCEDURE : ObjectType.FUNCTION, f.getKey()));
        }
        return keys;
    }

    /** The changes of one object in one database (current = this database, target = the baseline). */
    private static MatrixCellDto cell(RowKey key, List<ChangeItem> items) {
        MatrixCellStatus status = MatrixCellStatus.MODIFIED;
        for (ChangeItem item : items) {
            boolean self = item.getObjectType() == key.type() && key.name().equals(item.getObjectName());
            if (self && item.getChangeType() == ChangeType.ADDED) {
                status = MatrixCellStatus.MISSING;
            } else if (self && item.getChangeType() == ChangeType.DELETED) {
                status = MatrixCellStatus.EXTRA_ONLY_HERE;
            }
        }
        List<String> differences = new ArrayList<>();
        List<String> up = new ArrayList<>();
        List<String> down = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (ChangeItem item : items) {
            differences.add(item.getChangeType() + " " + item.getObjectType() + " " + item.getObjectName()
                    + (item.getDetails() == null || item.getDetails().isBlank() ? "" : ": " + item.getDetails()));
            if (item.getUpSql() != null && !item.getUpSql().isBlank()) {
                up.add(item.getUpSql());
            }
            if (item.getDownSql() != null && !item.getDownSql().isBlank()) {
                down.add(item.getDownSql());
            }
            warnings.addAll(item.getWarnings());
        }
        return new MatrixCellDto(status, differences, String.join("\n", up), String.join("\n", down), warnings);
    }
}
