package com.vodianoy.pgvc.introspection;

import com.vodianoy.pgvc.common.ApiException;
import com.vodianoy.pgvc.environment.EnvironmentManagerService;
import com.vodianoy.pgvc.model.schema.ColumnModel;
import com.vodianoy.pgvc.model.schema.ConstraintModel;
import com.vodianoy.pgvc.model.schema.ConstraintType;
import com.vodianoy.pgvc.model.schema.DatabaseSnapshot;
import com.vodianoy.pgvc.model.schema.IndexModel;
import com.vodianoy.pgvc.model.schema.SequenceModel;
import com.vodianoy.pgvc.model.schema.TableModel;
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
        JdbcTemplate jdbc = environments.getJdbcTemplate(envId);
        TransactionTemplate tx = new TransactionTemplate(environments.getTransactionTemplate(envId).getTransactionManager());
        tx.setReadOnly(true);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return tx.execute(status -> read(jdbc, schemaFilter));
    }

    /** User schemas that can be version controlled (no system, temporary, extension or _pgvc schemas). */
    public List<String> listSchemas(String envId) {
        return environments.getJdbcTemplate(envId).queryForList(SCHEMAS, String.class);
    }

    // ------------------------------------------------------------------ assembly

    private DatabaseSnapshot read(JdbcTemplate jdbc, Collection<String> schemaFilter) {
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
                    .build();
            snapshot.getSequences().put(s.getQualifiedName(), s);
        });

        return snapshot;
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
