package com.vodianoy.pgvc.refdata;

import com.vodianoy.pgvc.common.ApiException;
import com.vodianoy.pgvc.environment.EnvironmentManagerService;
import com.vodianoy.pgvc.introspection.PostgresSchemaIntrospector;
import com.vodianoy.pgvc.model.schema.ConstraintType;
import com.vodianoy.pgvc.model.schema.DatabaseSnapshot;
import com.vodianoy.pgvc.model.schema.ReferenceDataModel;
import com.vodianoy.pgvc.model.schema.TableModel;
import com.vodianoy.pgvc.storage.PgVcStorageService;
import com.vodianoy.pgvc.storage.TrackedDataTable;
import com.vodianoy.pgvc.util.SqlIdentifiers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Reference data (dictionaries): small tables whose rows are version controlled. A tracked table must have a
 * PRIMARY KEY (rows are matched by it) and at most {@link ReferenceDataModel#MAX_ROWS} rows. Its rows become part of
 * every snapshot of the environment; the diff engine turns row changes into {@code INSERT ... ON CONFLICT} /
 * {@code DELETE} statements.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReferenceDataService {

    private final EnvironmentManagerService environments;
    private final PgVcStorageService storage;
    private final PostgresSchemaIntrospector introspector;

    /** Tables of the environment (partitions are not listed), tracked ones first. */
    public List<ReferenceDataTableView> list(String envId) {
        environments.getEnvironment(envId);
        Map<String, TrackedDataTable> tracked = storage.listTrackedTables(envId).stream()
                .collect(Collectors.toMap(TrackedDataTable::key, Function.identity(), (a, b) -> a));
        DatabaseSnapshot snapshot = introspector.introspect(envId);
        List<ReferenceDataTableView> result = new ArrayList<>();
        for (TableModel t : snapshot.getTables().values()) {
            if (t.isPartition()) {
                continue;
            }
            TrackedDataTable tr = tracked.get(t.getQualifiedName());
            boolean pk = hasPrimaryKey(t);
            ReferenceDataModel data = t.getReferenceData();
            result.add(new ReferenceDataTableView(t.getQualifiedName(), t.getSchemaName(), t.getName(), tr != null, pk,
                    t.getEstimatedRows(), data == null ? null : data.getRowCount(), data != null && data.isTruncated(),
                    pk && t.getEstimatedRows() <= ReferenceDataModel.MAX_ROWS, t.getColumns().size(),
                    tr == null ? null : tr.trackedBy(), tr == null ? null : tr.trackedAt(), ReferenceDataModel.MAX_ROWS));
        }
        result.sort(Comparator.comparing((ReferenceDataTableView v) -> !v.tracked()).thenComparing(ReferenceDataTableView::key));
        return result;
    }

    /** Marks a table as reference data. */
    public ReferenceDataTableView track(String envId, TrackTableRequest request) {
        String key = request == null || request.table() == null ? "" : request.table().strip();
        if (!storage.getStatus(envId).initialized()) {
            throw ApiException.conflict("refdata.notInitialized", envId);
        }
        DatabaseSnapshot snapshot = introspector.introspect(envId);
        TableModel t = snapshot.getTables().get(key);
        if (t == null || t.isPartition()) {
            throw ApiException.notFound("refdata.unknownTable", key, envId);
        }
        if (!hasPrimaryKey(t)) {
            throw ApiException.conflict("refdata.noPrimaryKey", key);
        }
        Long rows = environments.getJdbcTemplate(envId).queryForObject("SELECT count(*) FROM (SELECT 1 FROM "
                + SqlIdentifiers.qualified(t.getSchemaName(), t.getName()) + " LIMIT " + (ReferenceDataModel.MAX_ROWS + 1)
                + ") x", Long.class);
        if (rows != null && rows > ReferenceDataModel.MAX_ROWS) {
            throw ApiException.conflict("refdata.tooManyRows", key, ReferenceDataModel.MAX_ROWS);
        }
        String author = request.author() == null || request.author().isBlank() ? null : request.author().strip();
        storage.trackTable(envId, t.getSchemaName(), t.getName(), author);
        log.info("{}: {} is tracked as reference data ({} rows)", envId, key, rows);
        return view(envId, key);
    }

    /** Stops tracking the rows of a table (its schema stays version controlled). */
    public void untrack(String envId, String key) {
        String k = key == null ? "" : key.strip();
        int dot = k.indexOf('.');
        if (dot <= 0 || !storage.untrackTable(envId, k.substring(0, dot), k.substring(dot + 1))) {
            throw ApiException.notFound("refdata.notTracked", k, envId);
        }
        log.info("{}: {} is no longer tracked as reference data", envId, k);
    }

    private ReferenceDataTableView view(String envId, String key) {
        return list(envId).stream().filter(v -> v.key().equals(key)).findFirst()
                .orElseThrow(() -> ApiException.notFound("refdata.unknownTable", key, envId));
    }

    private static boolean hasPrimaryKey(TableModel t) {
        return t.getConstraints().values().stream().anyMatch(c -> c.getType() == ConstraintType.PRIMARY_KEY);
    }
}
