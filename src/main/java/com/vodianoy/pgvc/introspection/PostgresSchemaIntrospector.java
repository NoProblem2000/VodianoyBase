package com.vodianoy.pgvc.introspection;

import com.vodianoy.pgvc.common.ApiException;
import com.vodianoy.pgvc.environment.EnvironmentManagerService;
import com.vodianoy.pgvc.util.SqlIdentifiers;
import com.vodianoy.pgvc.model.schema.ColumnModel;
import com.vodianoy.pgvc.model.schema.ConstraintModel;
import com.vodianoy.pgvc.model.schema.ConstraintType;
import com.vodianoy.pgvc.model.schema.DatabaseSnapshot;
import com.vodianoy.pgvc.model.schema.EnumTypeModel;
import com.vodianoy.pgvc.model.schema.ExtensionModel;
import com.vodianoy.pgvc.model.schema.FunctionModel;
import com.vodianoy.pgvc.model.schema.IndexModel;
import com.vodianoy.pgvc.model.schema.ReferenceDataModel;
import com.vodianoy.pgvc.model.schema.SequenceModel;
import com.vodianoy.pgvc.model.schema.TableModel;
import com.vodianoy.pgvc.model.schema.TriggerModel;
import com.vodianoy.pgvc.model.schema.ViewDependency;
import com.vodianoy.pgvc.model.schema.ViewModel;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the complete schema of an environment from pg_catalog (never information_schema, which hides partitions
 * and OIDs) into a {@link DatabaseSnapshot}. Works on PostgreSQL 11+.
 *
 * <p>Covers tables (with columns, constraints, indexes, triggers), partitions, views, materialized views, sequences,
 * functions / procedures, ENUM types and extensions, plus comments, owners, privileges and tablespaces. Ignore rules
 * are not applied here: the snapshot is complete, the diff engine filters it.
 *
 * <p>All queries run in one read-only REPEATABLE READ transaction, so they see one consistent state of the catalog.
 * When a transaction is already open on the environment (e.g. a migration that has just executed DDL), the
 * introspector joins it and sees the uncommitted changes.
 */
@Service
@RequiredArgsConstructor
public class PostgresSchemaIntrospector {

    private static final String SQL_DIR = "db/introspection/";
    private static final String SCHEMAS = sql("schemas.sql");
    private static final String IDENTITY = sql("database-identity.sql");
    private static final String TABLES = sql("tables.sql");
    private static final String COLUMNS = sql("columns.sql");
    private static final String CONSTRAINTS = sql("constraints.sql");
    private static final String INDEXES = sql("indexes.sql");
    private static final String SEQUENCES = sql("sequences.sql");
    private static final String VIEWS = sql("views.sql");
    private static final String VIEW_DEPENDENCIES = sql("view-dependencies.sql");
    private static final String FUNCTIONS = sql("functions.sql");
    private static final String TRIGGERS = sql("triggers.sql");
    private static final String ENUMS = sql("enums.sql");
    private static final String EXTENSIONS = sql("extensions.sql");

    private final EnvironmentManagerService environments;

    /** Snapshot of all user schemas of the environment. */
    public DatabaseSnapshot introspect(String envId) {
        return introspect(envId, null);
    }

    /**
     * Snapshot of the given schemas of the environment.
     *
     * @param schemaFilter schemas to include; null or empty means all user schemas
     */
    public DatabaseSnapshot introspect(String envId, Collection<String> schemaFilter) {
        return introspect(envId, schemaFilter, Set.of());
    }

    /**
     * Snapshot of the given schemas, with the rows of the tables tracked as reference data in this environment
     * ({@code _pgvc.tracked_data_tables}) and of {@code extraDataTables} (keys {@code schema.table}; used when the
     * database is compared with a snapshot or a database that tracks them).
     */
    public DatabaseSnapshot introspect(String envId, Collection<String> schemaFilter, Collection<String> extraDataTables) {
        JdbcTemplate jdbc = environments.getJdbcTemplate(envId);
        TransactionTemplate tx = new TransactionTemplate(environments.getTransactionTemplate(envId).getTransactionManager());
        tx.setReadOnly(true);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        DatabaseSnapshot snapshot = tx.execute(status -> read(jdbc, schemaFilter,
                extraDataTables == null ? Set.of() : new LinkedHashSet<>(extraDataTables)));
        if (snapshot != null && snapshot.getSourceDatabaseId() != null) {
            // "system_identifier:database_oid@environment": physical copies of a cluster (pg_basebackup, one Docker
            // image with a prepared database, a restored backup) share system_identifier and database oid; the
            // environment id keeps their OIDs / attnums from being matched with each other
            snapshot.setSourceDatabaseId(snapshot.getSourceDatabaseId() + "@" + envId);
        }
        return snapshot;
    }

    /** Snapshot of all user schemas plus the rows of {@code extraDataTables}. */
    public DatabaseSnapshot introspectWithData(String envId, Collection<String> extraDataTables) {
        return introspect(envId, null, extraDataTables);
    }

    /** Keys of the tables whose rows a snapshot contains. */
    public static Set<String> dataTables(DatabaseSnapshot snapshot) {
        Set<String> keys = new LinkedHashSet<>();
        if (snapshot != null) {
            snapshot.getTables().forEach((k, t) -> {
                if (t.getReferenceData() != null) {
                    keys.add(k);
                }
            });
        }
        return keys;
    }

    /** User schemas that can be version controlled (no system, temporary, extension or _pgvc schemas). */
    public List<String> listSchemas(String envId) {
        return environments.getJdbcTemplate(envId).queryForList(SCHEMAS, String.class);
    }

    // ------------------------------------------------------------------ assembly

    private DatabaseSnapshot read(JdbcTemplate jdbc, Collection<String> schemaFilter, Set<String> extraDataTables) {
        List<String> schemas = resolveSchemas(jdbc, schemaFilter);
        Map<String, Object> identity = jdbc.queryForMap(IDENTITY);
        int serverVersion = ((Number) identity.get("server_version")).intValue();

        DatabaseSnapshot snapshot = DatabaseSnapshot.builder()
                .timestamp(OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS))
                .sourceDatabaseId((String) identity.get("source_database_id"))
                .serverVersion(serverVersion)
                .schemas(schemas)
                .build();
        if (schemas.isEmpty()) {
            return snapshot;
        }
        String[] schemaArray = schemas.toArray(String[]::new);

        Map<Long, TableModel> tablesByOid = new HashMap<>();
        query(jdbc, TABLES, schemaArray, rs -> {
            TableModel t = TableModel.builder()
                    .oid(rs.getLong("table_oid"))
                    .schemaName(rs.getString("schema_name"))
                    .name(rs.getString("table_name"))
                    .partitioned(rs.getBoolean("partitioned"))
                    .partitionKeyDef(rs.getString("partition_key_def"))
                    .parentTable(qualified(rs.getString("parent_schema"), rs.getString("parent_table")))
                    .partitionBoundExpr(rs.getString("partition_bound_expr"))
                    .estimatedRows(rs.getLong("estimated_rows"))
                    .totalSizeBytes(rs.getLong("total_size_bytes"))
                    .prettySize(rs.getString("pretty_size"))
                    .comment(rs.getString("comment"))
                    .owner(rs.getString("owner"))
                    .acl(stringList(rs.getArray("acl")))
                    .tablespace(rs.getString("tablespace_name"))
                    .build();
            tablesByOid.put(t.getOid(), t);
            snapshot.getTables().put(t.getQualifiedName(), t);
        });

        String columnsSql = COLUMNS.replace("{{ATTGENERATED}}", serverVersion >= 120000 ? "a.attgenerated" : "''");
        query(jdbc, columnsSql, schemaArray, rs -> {
            TableModel t = tablesByOid.get(rs.getLong("table_oid"));
            if (t == null) {
                return;
            }
            boolean generated = rs.getBoolean("generated_stored");
            String expr = rs.getString("default_expr");
            ColumnModel c = ColumnModel.builder()
                    .attnum(rs.getInt("attnum"))
                    .name(rs.getString("column_name"))
                    .dataType(rs.getString("data_type"))
                    .nullable(rs.getBoolean("nullable"))
                    .defaultValue(generated ? null : expr)
                    .generatedExpression(generated ? expr : null)
                    .identityGeneration(rs.getString("identity_generation"))
                    .collation(rs.getString("collation_name"))
                    .inherited(rs.getBoolean("inherited"))
                    .comment(rs.getString("comment"))
                    .build();
            t.getColumns().put(c.getName(), c);
        });

        query(jdbc, CONSTRAINTS, schemaArray, rs -> {
            TableModel t = tablesByOid.get(rs.getLong("table_oid"));
            if (t == null) {
                return;
            }
            ConstraintModel c = ConstraintModel.builder()
                    .oid(rs.getLong("constraint_oid"))
                    .name(rs.getString("constraint_name"))
                    .type(ConstraintType.fromCode(rs.getString("constraint_type")))
                    .definition(rs.getString("definition"))
                    .columns(stringList(rs.getArray("column_names")))
                    .referencedTable(qualified(rs.getString("referenced_schema"), rs.getString("referenced_table")))
                    .referencedColumns(stringList(rs.getArray("referenced_columns")))
                    .deferrable(rs.getBoolean("deferrable"))
                    .initiallyDeferred(rs.getBoolean("initially_deferred"))
                    .validated(rs.getBoolean("validated"))
                    .signature(rs.getString("signature"))
                    .build();
            t.getConstraints().put(c.getName(), c);
        });

        Map<Long, ViewModel> viewsByOid = new HashMap<>();
        query(jdbc, VIEWS, schemaArray, rs -> {
            ViewModel v = ViewModel.builder()
                    .oid(rs.getLong("view_oid"))
                    .schemaName(rs.getString("schema_name"))
                    .name(rs.getString("view_name"))
                    .materialized(rs.getBoolean("materialized"))
                    .definition(rs.getString("definition"))
                    .populated(rs.getBoolean("populated"))
                    .signature(rs.getString("signature"))
                    .comment(rs.getString("comment"))
                    .owner(rs.getString("owner"))
                    .acl(stringList(rs.getArray("acl")))
                    .build();
            viewsByOid.put(v.getOid(), v);
            snapshot.getViews().put(v.getQualifiedName(), v);
        });

        query(jdbc, VIEW_DEPENDENCIES, schemaArray, rs -> {
            ViewModel v = viewsByOid.get(rs.getLong("view_oid"));
            if (v == null) {
                return;
            }
            v.getDependencies().add(ViewDependency.builder()
                    .relation(qualified(rs.getString("referenced_schema"), rs.getString("referenced_name")))
                    .relationKind(rs.getString("referenced_kind"))
                    .column(rs.getString("referenced_column"))
                    .build());
        });

        query(jdbc, INDEXES, schemaArray, rs -> {
            long owner = rs.getLong("table_oid");
            IndexModel i = IndexModel.builder()
                    .oid(rs.getLong("index_oid"))
                    .name(rs.getString("index_name"))
                    .definition(rs.getString("definition"))
                    .unique(rs.getBoolean("is_unique"))
                    .method(rs.getString("method"))
                    .predicate(rs.getString("predicate"))
                    .signature(rs.getString("signature"))
                    .build();
            if (tablesByOid.containsKey(owner)) {
                tablesByOid.get(owner).getIndexes().put(i.getName(), i);
            } else if (viewsByOid.containsKey(owner)) {
                viewsByOid.get(owner).getIndexes().put(i.getName(), i);
            }
        });

        query(jdbc, SEQUENCES, schemaArray, rs -> {
            SequenceModel s = SequenceModel.builder()
                    .oid(rs.getLong("sequence_oid"))
                    .schemaName(rs.getString("schema_name"))
                    .name(rs.getString("sequence_name"))
                    .dataType(rs.getString("data_type"))
                    .startValue(rs.getLong("start_value"))
                    .incrementBy(rs.getLong("increment_by"))
                    .minValue(rs.getLong("min_value"))
                    .maxValue(rs.getLong("max_value"))
                    .cacheSize(rs.getLong("cache_size"))
                    .cycle(rs.getBoolean("cycle"))
                    .ownedByTable(qualified(rs.getString("owned_by_schema"), rs.getString("owned_by_table")))
                    .ownedByColumn(rs.getString("owned_by_column"))
                    .owner(rs.getString("owner"))
                    .acl(stringList(rs.getArray("acl")))
                    .build();
            snapshot.getSequences().put(s.getQualifiedName(), s);
        });

        String triggersSql = TRIGGERS.replace("{{TRIGGER_CLONES}}", serverVersion >= 130000 ? "AND t.tgparentid = 0" : "");
        query(jdbc, triggersSql, schemaArray, rs -> {
            TableModel t = tablesByOid.get(rs.getLong("table_oid"));
            if (t == null) {
                return;
            }
            TriggerModel tr = TriggerModel.builder()
                    .oid(rs.getLong("trigger_oid"))
                    .name(rs.getString("trigger_name"))
                    .definition(rs.getString("definition"))
                    .enabled(rs.getString("enabled"))
                    .functionKey(FunctionModel.key(rs.getString("function_schema"), rs.getString("function_name"),
                            rs.getString("function_arguments")))
                    .build();
            t.getTriggers().put(tr.getName(), tr);
        });

        query(jdbc, FUNCTIONS, schemaArray, rs -> {
            FunctionModel f = FunctionModel.builder()
                    .oid(rs.getLong("function_oid"))
                    .schemaName(rs.getString("schema_name"))
                    .name(rs.getString("function_name"))
                    .identityArguments(rs.getString("identity_arguments"))
                    .kind(rs.getString("kind"))
                    .resultType(rs.getString("result_type"))
                    .language(rs.getString("language_name"))
                    .definition(rs.getString("definition"))
                    .owner(rs.getString("owner"))
                    .acl(stringList(rs.getArray("acl")))
                    .comment(rs.getString("comment"))
                    .build();
            snapshot.getFunctions().put(f.getKey(), f);
        });

        query(jdbc, ENUMS, schemaArray, rs -> {
            EnumTypeModel e = EnumTypeModel.builder()
                    .oid(rs.getLong("type_oid"))
                    .schemaName(rs.getString("schema_name"))
                    .name(rs.getString("type_name"))
                    .values(stringList(rs.getArray("enum_values")))
                    .owner(rs.getString("owner"))
                    .comment(rs.getString("comment"))
                    .build();
            snapshot.getEnumTypes().put(e.getQualifiedName(), e);
        });

        jdbc.query(EXTENSIONS, (RowCallbackHandler) rs -> {
            ExtensionModel e = ExtensionModel.builder()
                    .name(rs.getString("extension_name"))
                    .schemaName(rs.getString("schema_name"))
                    .version(rs.getString("extension_version"))
                    .build();
            snapshot.getExtensions().put(e.getName(), e);
        });

        aggregatePartitionStatistics(snapshot.getTables());

        Set<String> dataTables = new LinkedHashSet<>(trackedTables(jdbc));
        dataTables.addAll(extraDataTables);
        for (String key : dataTables) {
            TableModel t = snapshot.getTables().get(key);
            if (t != null) {
                t.setReferenceData(readRows(jdbc, t));
            }
        }
        return snapshot;
    }

    /** {@code schema.table} of _pgvc.tracked_data_tables (empty when the table does not exist). */
    private static List<String> trackedTables(JdbcTemplate jdbc) {
        Boolean exists = jdbc.queryForObject("SELECT to_regclass('_pgvc.tracked_data_tables') IS NOT NULL", Boolean.class);
        if (!Boolean.TRUE.equals(exists)) {
            return List.of();
        }
        return jdbc.queryForList("SELECT schema_name || '.' || table_name FROM _pgvc.tracked_data_tables"
                + " ORDER BY schema_name, table_name", String.class);
    }

    /**
     * Rows of a reference-data table ordered by its PRIMARY KEY, every value as text. Null when the table has no
     * primary key (its rows cannot be matched); {@code truncated} when it has more than {@link ReferenceDataModel#MAX_ROWS}.
     */
    static ReferenceDataModel readRows(JdbcTemplate jdbc, TableModel t) {
        ConstraintModel pk = t.getConstraints().values().stream()
                .filter(c -> c.getType() == ConstraintType.PRIMARY_KEY).findFirst().orElse(null);
        if (pk == null || pk.getColumns().isEmpty()) {
            return null;
        }
        List<ColumnModel> columns = new ArrayList<>(t.getColumns().values());
        columns.removeIf(c -> c.getGeneratedExpression() != null);
        columns.sort(java.util.Comparator.comparingInt(ColumnModel::getAttnum));
        StringBuilder select = new StringBuilder("SELECT ARRAY[");
        for (int i = 0; i < columns.size(); i++) {
            select.append(i == 0 ? "" : ", ").append("t.").append(SqlIdentifiers.ident(columns.get(i).getName())).append("::text");
        }
        select.append("]::text[] AS r FROM ").append(SqlIdentifiers.qualified(t.getSchemaName(), t.getName()))
                .append(" t ORDER BY ");
        for (int i = 0; i < pk.getColumns().size(); i++) {
            select.append(i == 0 ? "" : ", ").append("t.").append(SqlIdentifiers.ident(pk.getColumns().get(i)));
        }
        select.append(" LIMIT ").append(ReferenceDataModel.MAX_ROWS + 1);
        List<List<String>> rows = new ArrayList<>();
        jdbc.query(select.toString(), (RowCallbackHandler) rs -> rows.add(new ArrayList<>(stringList(rs.getArray("r")))));
        boolean truncated = rows.size() > ReferenceDataModel.MAX_ROWS;
        List<List<String>> kept = truncated ? new ArrayList<>() : rows;
        return ReferenceDataModel.builder()
                .columns(new ArrayList<>(columns.stream().map(ColumnModel::getName).toList()))
                .columnTypes(new ArrayList<>(columns.stream().map(ColumnModel::getDataType).toList()))
                .primaryKey(new ArrayList<>(pk.getColumns()))
                .rows(kept)
                .rowCount(rows.size())
                .truncated(truncated)
                .rowsHash(ReferenceDataModel.hash(kept))
                .build();
    }

    /**
     * A partitioned table stores nothing itself: its rows and size are the sums over its partitions (recursively,
     * sub-partitioned partitions included).
     */
    static void aggregatePartitionStatistics(Map<String, TableModel> tables) {
        Map<String, List<TableModel>> children = new HashMap<>();
        for (TableModel t : tables.values()) {
            if (t.getParentTable() != null) {
                children.computeIfAbsent(t.getParentTable(), k -> new java.util.ArrayList<>()).add(t);
            }
        }
        for (TableModel t : tables.values()) {
            if (t.isPartitioned() && t.getParentTable() == null) {
                sumPartitions(t, children);
            }
        }
    }

    private static long[] sumPartitions(TableModel t, Map<String, List<TableModel>> children) {
        if (!t.isPartitioned()) {
            return new long[]{t.getEstimatedRows(), t.getTotalSizeBytes()};
        }
        long rows = 0;
        long bytes = t.getTotalSizeBytes();
        for (TableModel child : children.getOrDefault(t.getQualifiedName(), List.of())) {
            long[] sub = sumPartitions(child, children);
            rows += sub[0];
            bytes += sub[1];
        }
        t.setEstimatedRows(rows);
        t.setTotalSizeBytes(bytes);
        t.setPrettySize(prettySize(bytes));
        return new long[]{rows, bytes};
    }

    /** Same output as PostgreSQL's pg_size_pretty(bigint). */
    public static String prettySize(long bytes) {
        long size = bytes;
        long limit = 10 * 1024;
        long limit2 = limit * 2 - 1;
        if (Math.abs(size) < limit) {
            return size + " bytes";
        }
        String[] units = {"kB", "MB", "GB", "TB", "PB"};
        size >>= 9; // keep one extra bit for rounding
        for (int i = 0; i < units.length; i++) {
            if (Math.abs(size) < limit2 || i == units.length - 1) {
                return ((size + 1) / 2) + " " + units[i];
            }
            size >>= 10;
        }
        return bytes + " bytes";
    }

    private List<String> resolveSchemas(JdbcTemplate jdbc, Collection<String> filter) {
        List<String> available = jdbc.queryForList(SCHEMAS, String.class);
        if (filter == null || filter.isEmpty()) {
            return available;
        }
        Set<String> wanted = new LinkedHashSet<>(filter);
        List<String> unknown = wanted.stream().filter(s -> !available.contains(s)).toList();
        if (!unknown.isEmpty()) {
            throw ApiException.badRequest("schema.unknown", String.join(", ", unknown), String.join(", ", available));
        }
        return available.stream().filter(wanted::contains).toList();
    }

    // ------------------------------------------------------------------ helpers

    @FunctionalInterface
    private interface RowConsumer {
        void accept(ResultSet rs) throws SQLException;
    }

    private static void query(JdbcTemplate jdbc, String sql, String[] schemas, RowConsumer consumer) {
        PreparedStatementSetter params = ps -> ps.setArray(1, ps.getConnection().createArrayOf("text", schemas));
        jdbc.query(sql, params, (RowCallbackHandler) consumer::accept);
    }

    private static List<String> stringList(Array array) throws SQLException {
        if (array == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Arrays.asList((String[]) array.getArray()));
    }

    private static String qualified(String schema, String name) {
        return schema == null || name == null ? null : DatabaseSnapshot.key(schema, name);
    }

    private static String sql(String file) {
        try {
            return new ClassPathResource(SQL_DIR + file).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Missing classpath resource " + SQL_DIR + file, e);
        }
    }
}
