package com.vodianoy.pgvc.diff;

import com.vodianoy.pgvc.model.schema.ColumnModel;
import com.vodianoy.pgvc.model.schema.ConstraintModel;
import com.vodianoy.pgvc.model.schema.ConstraintType;
import com.vodianoy.pgvc.model.schema.DatabaseSnapshot;
import com.vodianoy.pgvc.model.schema.IndexModel;
import com.vodianoy.pgvc.model.schema.SequenceModel;
import com.vodianoy.pgvc.model.schema.TableModel;
import com.vodianoy.pgvc.model.schema.ViewDependency;
import com.vodianoy.pgvc.model.schema.ViewModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static com.vodianoy.pgvc.util.SqlIdentifiers.ident;
import static com.vodianoy.pgvc.util.SqlIdentifiers.qualified;
import static com.vodianoy.pgvc.util.SqlIdentifiers.qualifiedKey;

/**
 * Compares the HEAD snapshot with the live snapshot and produces the list of changes plus the UP (head -> live) and
 * DOWN (live -> head) migrations.
 *
 * <p>Renames are recognised by identity: a table / view / sequence / index / constraint that keeps its OID, or a
 * column that keeps its attnum, but changes its name becomes {@code ALTER ... RENAME}. Identities are only compared
 * when both snapshots come from the same database.
 *
 * <p>Statements are emitted in a dependency-safe order: views that read changed columns are dropped first
 * ({@code DROP VIEW IF EXISTS}) and recreated last ({@code CREATE OR REPLACE VIEW}); partitioned parents are created
 * before their partitions; foreign keys are dropped before tables and added after all other constraints.
 */
@Component
public class SchemaDiffEngine {

    public SchemaDiffResult diff(DatabaseSnapshot headSnapshot, DatabaseSnapshot liveSnapshot) {
        DatabaseSnapshot head = headSnapshot == null ? DatabaseSnapshot.builder().build() : headSnapshot;
        DatabaseSnapshot live = liveSnapshot == null ? DatabaseSnapshot.builder().build() : liveSnapshot;
        Run forward = new Run(head, live, true);
        forward.execute();
        Run reverse = new Run(live, head, false);
        reverse.execute();

        Map<String, ChangeItem> inverseByKey = new HashMap<>();
        for (ChangeItem item : reverse.items) {
            inverseByKey.putIfAbsent(item.getPairKey() + "|" + item.getChangeType(), item);
        }
        for (ChangeItem item : forward.items) {
            item.setUpSql(forward.sqlOf(item));
            ChangeItem inverse = inverseByKey.get(item.getPairKey() + "|" + item.getChangeType().inverse());
            item.setDownSql(inverse == null ? "" : reverse.sqlOf(inverse));
        }
        return SchemaDiffResult.builder()
                .hasChanges(!forward.statements.isEmpty())
                .changes(forward.items)
                .upSql(forward.script())
                .downSql(reverse.script())
                .warnings(forward.warnings)
                .build();
    }

    /** One direction of the comparison ("from" -> "to"). */
    private static final class Run {

        // execution order of the generated statements
        private static final int CREATE_SCHEMA = 0;
        private static final int DROP_VIEW = 1;
        private static final int RELEASE_OWNERSHIP = 2;
        private static final int DROP_FOREIGN_KEY = 3;
        private static final int DROP_CONSTRAINT = 4;
        private static final int DROP_INDEX = 5;
        private static final int DETACH_PARTITION = 6;
        private static final int DROP_TABLE = 7;
        private static final int RENAME_RELATION = 8;
        private static final int RENAME_COLUMN = 9;
        private static final int RENAME_OTHER = 10;
        private static final int DROP_SEQUENCE = 11;
        private static final int CREATE_SEQUENCE = 12;
        private static final int ALTER_SEQUENCE = 13;
        private static final int CREATE_TABLE = 14;
        private static final int ALTER_TABLE = 15;
        private static final int ATTACH_PARTITION = 16;
        private static final int ADD_CONSTRAINT = 17;
        private static final int ADD_FOREIGN_KEY = 18;
        private static final int CREATE_INDEX = 19;
        private static final int SEQUENCE_OWNERSHIP = 20;
        private static final int CREATE_VIEW = 21;
        private static final int CREATE_VIEW_INDEX = 22;
        private static final int DROP_SCHEMA = 23;

        private static final String DROPPED = "\u0000dropped:";

        private record Statement(int phase, int seq, ChangeItem item, String sql) {
        }

        private final DatabaseSnapshot from;
        private final DatabaseSnapshot to;
        /** true: "to" is the live database (forward diff); false: "from" is live (reverse diff). */
        private final boolean liveIsTo;
        private final boolean sameDb;

        final List<ChangeItem> items = new ArrayList<>();
        final List<Statement> statements = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();

        /** from-key of a table / view -> to-key, or DROPPED marker. */
        private final Map<String, String> relationMapping = new HashMap<>();
        private final Set<String> droppedTables = new HashSet<>();
        /** "schema.table.column" (from names) whose type changes or which are dropped. */
        private final Set<String> affectedColumns = new HashSet<>();
        private final Set<String> affectedTables = new HashSet<>();
        /** Why a view has to be recreated, by from-key. */
        private final Map<String, String> viewRecreateReason = new HashMap<>();

        private Matching<TableModel> tables;
        private Matching<ViewModel> views;

        Run(DatabaseSnapshot from, DatabaseSnapshot to, boolean liveIsTo) {
            this.from = from;
            this.to = to;
            this.liveIsTo = liveIsTo;
            String a = from.getSourceDatabaseId();
            this.sameDb = a != null && !a.startsWith("unknown") && a.equals(to.getSourceDatabaseId());
        }

        void execute() {
            tables = Matching.of(sorted(from.getTables()), sorted(to.getTables()), TableModel::getOid,
                    TableModel::getQualifiedName, sameDb);
            views = Matching.of(sorted(from.getViews()), sorted(to.getViews()), ViewModel::getOid,
                    ViewModel::getQualifiedName, sameDb);
            for (Matching.Pair<TableModel> p : tables.matched) {
                relationMapping.put(p.from().getQualifiedName(), p.to().getQualifiedName());
            }
            for (TableModel t : tables.removed) {
                relationMapping.put(t.getQualifiedName(), DROPPED + t.getQualifiedName());
                droppedTables.add(t.getQualifiedName());
            }
            for (Matching.Pair<ViewModel> p : views.matched) {
                relationMapping.put(p.from().getQualifiedName(), p.to().getQualifiedName());
            }
            for (ViewModel v : views.removed) {
                relationMapping.put(v.getQualifiedName(), DROPPED + v.getQualifiedName());
            }

            diffSchemas();
            diffTables();
            diffViews();
            diffSequences();
        }

        // ------------------------------------------------------------------ bookkeeping

        private static <T> Collection<T> sorted(Map<String, T> map) {
            return new TreeMap<>(map == null ? Map.of() : map).values();
        }

        private String mapRelation(String fromKey) {
            String mapped = relationMapping.get(fromKey);
            return mapped == null ? fromKey : mapped;
        }

        /** Identity shared by a change and its inverse in the reverse diff. */
        private String pairKey(String facet, String fromId, String toId) {
            String liveId = liveIsTo ? toId : fromId;
            String headId = liveIsTo ? fromId : toId;
            return facet + (liveId != null ? ":L" + liveId : ":H" + headId);
        }

        private static String id(long oid) {
            return Long.toString(oid);
        }

        private ChangeItem item(ChangeType type, ObjectType objectType, String name, String details, String pairKey) {
            ChangeItem item = ChangeItem.builder().changeType(type).objectType(objectType).objectName(name)
                    .details(details).pairKey(pairKey).build();
            items.add(item);
            return item;
        }

        private void emit(int phase, ChangeItem item, String sql) {
            statements.add(new Statement(phase, statements.size(), item, sql));
        }

        private List<Statement> ordered() {
            List<Statement> list = new ArrayList<>(statements);
            list.sort(Comparator.comparingInt(Statement::phase).thenComparingInt(Statement::seq));
            return list;
        }

        String sqlOf(ChangeItem item) {
            return ordered().stream().filter(s -> s.item() == item).map(Statement::sql)
                    .collect(Collectors.joining("\n"));
        }

        String script() {
            StringBuilder sb = new StringBuilder();
            for (String w : warnings) {
                sb.append("-- WARNING: ").append(w.replace("\n", " ")).append('\n');
            }
            if (!warnings.isEmpty()) {
                sb.append('\n');
            }
            if (statements.isEmpty()) {
                return sb.append("-- No schema changes\n").toString();
            }
            for (Statement s : ordered()) {
                sb.append(s.sql()).append('\n');
            }
            return sb.toString();
        }

        // ------------------------------------------------------------------ schemas

        private void diffSchemas() {
            Set<String> fromSchemas = new HashSet<>(from.getSchemas());
            Set<String> toSchemas = new HashSet<>(to.getSchemas());
            for (String s : new TreeSet<>(toSchemas)) {
                if (!fromSchemas.contains(s) && !"public".equals(s)) {
                    ChangeItem item = item(ChangeType.ADDED, ObjectType.SCHEMA, s, "new schema", pairKey("schema", null, s));
                    emit(CREATE_SCHEMA, item, "CREATE SCHEMA IF NOT EXISTS " + ident(s) + ";");
                }
            }
            for (String s : new TreeSet<>(fromSchemas)) {
                if (!toSchemas.contains(s) && !"public".equals(s)) {
                    ChangeItem item = item(ChangeType.DELETED, ObjectType.SCHEMA, s, "schema dropped", pairKey("schema", s, null));
                    emit(DROP_SCHEMA, item, "DROP SCHEMA " + ident(s) + ";");
                }
            }
        }

        // ------------------------------------------------------------------ tables

        private static ObjectType tableType(TableModel t) {
            return t.isPartition() ? ObjectType.PARTITION : ObjectType.TABLE;
        }

        private void diffTables() {
            for (TableModel f : tables.removed) {
                String key = f.getQualifiedName();
                ChangeItem item = item(ChangeType.DELETED, tableType(f), key, "dropped, its data will be lost",
                        pairKey("table", id(f.getOid()), null));
                warnings.add((f.isPartition() ? "Partition " : "Table ") + key + " is dropped: its data will be lost.");
                for (ConstraintModel c : sorted(f.getConstraints())) {
                    if (c.getType() == ConstraintType.FOREIGN_KEY) {
                        emit(DROP_FOREIGN_KEY, item, "ALTER TABLE " + qualified(f.getSchemaName(), f.getName())
                                + " DROP CONSTRAINT " + ident(c.getName()) + ";");
                    }
                }
                boolean droppedWithParent = f.isPartition() && droppedTables.contains(f.getParentTable());
                if (droppedWithParent) {
                    emit(DROP_TABLE, item, "-- " + key + " is dropped together with its parent " + f.getParentTable());
                } else {
                    emit(DROP_TABLE, item, "DROP TABLE " + qualified(f.getSchemaName(), f.getName()) + ";");
                }
            }

            Map<String, TableModel> toByKey = to.getTables();
            List<TableModel> added = new ArrayList<>(tables.added);
            added.sort(Comparator.comparingInt((TableModel t) -> depth(t, toByKey)).thenComparing(TableModel::getQualifiedName));
            for (TableModel t : added) {
                String details = t.isPartition()
                        ? "new partition of " + t.getParentTable() + " " + t.getPartitionBoundExpr()
                        : t.isPartitioned() ? "new table partitioned by " + t.getPartitionKeyDef() : "new table";
                ChangeItem item = item(ChangeType.ADDED, tableType(t), t.getQualifiedName(), details,
                        pairKey("table", null, id(t.getOid())));
                emit(CREATE_TABLE, item, createTable(t));
                for (ConstraintModel c : sorted(t.getConstraints())) {
                    emit(c.getType() == ConstraintType.FOREIGN_KEY ? ADD_FOREIGN_KEY : ADD_CONSTRAINT, item, addConstraint(t, c));
                }
                for (IndexModel i : sorted(t.getIndexes())) {
                    emit(CREATE_INDEX, item, createIndex(i));
                }
            }

            for (Matching.Pair<TableModel> p : tables.matched) {
                diffTable(p.from(), p.to());
            }
        }

        private static int depth(TableModel t, Map<String, TableModel> byKey) {
            int d = 0;
            TableModel cur = t;
            Set<String> seen = new HashSet<>();
            while (cur != null && cur.isPartition() && seen.add(cur.getQualifiedName())) {
                d++;
                cur = byKey.get(cur.getParentTable());
            }
            return d;
        }

        private String createTable(TableModel t) {
            StringBuilder sb = new StringBuilder("CREATE TABLE ").append(qualified(t.getSchemaName(), t.getName()));
            if (t.isPartition()) {
                sb.append(" PARTITION OF ").append(qualifiedKey(t.getParentTable()))
                        .append("\n    ").append(t.getPartitionBoundExpr());
            } else {
                sb.append(" (");
                List<ColumnModel> columns = new ArrayList<>(t.getColumns().values());
                columns.sort(Comparator.comparingInt(ColumnModel::getAttnum));
                for (int i = 0; i < columns.size(); i++) {
                    sb.append(i == 0 ? "\n" : ",\n").append("    ").append(columnDefinition(columns.get(i)));
                }
                sb.append(columns.isEmpty() ? ")" : "\n)");
            }
            if (t.isPartitioned()) {
                sb.append("\n    PARTITION BY ").append(t.getPartitionKeyDef());
            }
            return sb.append(';').toString();
        }

        private static String columnDefinition(ColumnModel c) {
            StringBuilder sb = new StringBuilder(ident(c.getName())).append(' ').append(c.getDataType());
            if (c.getCollation() != null) {
                sb.append(" COLLATE ").append(c.getCollation());
            }
            if (c.getGeneratedExpression() != null) {
                sb.append(" GENERATED ALWAYS AS (").append(c.getGeneratedExpression()).append(") STORED");
            } else if (c.getIdentityGeneration() != null) {
                sb.append(" GENERATED ").append(c.getIdentityGeneration()).append(" AS IDENTITY");
            }
            if (c.getDefaultValue() != null) {
                sb.append(" DEFAULT ").append(c.getDefaultValue());
            }
            if (!c.isNullable() && c.getIdentityGeneration() == null) {
                sb.append(" NOT NULL");
            }
            return sb.toString();
        }

        private static String addConstraint(TableModel t, ConstraintModel c) {
            return "ALTER TABLE " + qualified(t.getSchemaName(), t.getName()) + " ADD CONSTRAINT " + ident(c.getName())
                    + " " + c.getDefinition() + ";";
        }

        /** pg_get_indexdef of a partitioned table says "ON ONLY": strip it so the index cascades to the partitions. */
        private static String createIndex(IndexModel i) {
            String def = i.getDefinition().strip().replaceFirst(" ON ONLY ", " ON ");
            return def.endsWith(";") ? def : def + ";";
        }

        private void diffTable(TableModel f, TableModel t) {
            String fq = qualified(f.getSchemaName(), f.getName());
            String tq = qualified(t.getSchemaName(), t.getName());
            String tableId = id(t.getOid());
            String fromId = id(f.getOid());

            // rename / move to another schema (same pg_class.oid, different name)
            if (!f.getSchemaName().equals(t.getSchemaName()) || !f.getName().equals(t.getName())) {
                ChangeItem item = item(ChangeType.RENAMED, tableType(t), t.getQualifiedName(),
                        "renamed from " + f.getQualifiedName(), pairKey("table-rename", fromId, tableId));
                if (!f.getSchemaName().equals(t.getSchemaName())) {
                    emit(RENAME_RELATION, item, "ALTER TABLE " + fq + " SET SCHEMA " + ident(t.getSchemaName()) + ";");
                }
                if (!f.getName().equals(t.getName())) {
                    emit(RENAME_RELATION, item, "ALTER TABLE " + qualified(t.getSchemaName(), f.getName())
                            + " RENAME TO " + ident(t.getName()) + ";");
                }
            }

            // partitioning scheme cannot be altered in place
            if (f.isPartitioned() != t.isPartitioned() || !Objects.equals(f.getPartitionKeyDef(), t.getPartitionKeyDef())) {
                String msg = "Partitioning of " + t.getQualifiedName() + " changed (" + describePartitioning(f) + " -> "
                        + describePartitioning(t) + "). PostgreSQL cannot change it in place: recreate the table manually.";
                warnings.add(msg);
                ChangeItem item = item(ChangeType.MODIFIED, tableType(t), t.getQualifiedName(), msg,
                        pairKey("table-partitioning", fromId, tableId));
                emit(ALTER_TABLE, item, "-- MANUAL ACTION REQUIRED: " + msg);
            }

            // partition membership / bounds
            String fromParentAsTo = f.isPartition() ? mapRelation(f.getParentTable()) : null;
            if (!Objects.equals(fromParentAsTo, t.getParentTable())
                    || !Objects.equals(f.getPartitionBoundExpr(), t.getPartitionBoundExpr())) {
                List<String> what = new ArrayList<>();
                if (f.isPartition()) {
                    what.add("detached from " + f.getParentTable());
                }
                if (t.isPartition()) {
                    what.add("attached to " + t.getParentTable() + " " + t.getPartitionBoundExpr());
                }
                ChangeItem item = item(ChangeType.MODIFIED, ObjectType.PARTITION, t.getQualifiedName(),
                        String.join(", ", what), pairKey("partition-of", fromId, tableId));
                if (f.isPartition()) {
                    emit(DETACH_PARTITION, item, "ALTER TABLE " + qualifiedKey(f.getParentTable()) + " DETACH PARTITION "
                            + fq + ";");
                }
                if (t.isPartition()) {
                    emit(ATTACH_PARTITION, item, "ALTER TABLE " + qualifiedKey(t.getParentTable()) + " ATTACH PARTITION "
                            + tq + "\n    " + t.getPartitionBoundExpr() + ";");
                }
            }

            // columns of a partition always follow the partitioned parent
            if (!(f.isPartition() && t.isPartition())) {
                diffColumns(f, t, tq);
            }
            diffConstraints(f, t, fq, tq);
            diffIndexes(f.getIndexes(), t.getIndexes(), f.getSchemaName(), t.getSchemaName(), t.getQualifiedName(),
                    sameDb && f.getOid() == t.getOid(), CREATE_INDEX);
        }

        private static String describePartitioning(TableModel t) {
            return t.isPartitioned() ? "PARTITION BY " + t.getPartitionKeyDef() : "not partitioned";
        }

        private void diffColumns(TableModel f, TableModel t, String tq) {
            boolean byAttnum = sameDb && f.getOid() == t.getOid();
            Matching<ColumnModel> cm = Matching.of(byAttnumOrder(f), byAttnumOrder(t), c -> (long) c.getAttnum(),
                    ColumnModel::getName, byAttnum);
            String fTableId = id(f.getOid());
            String tTableId = id(t.getOid());

            for (ColumnModel c : cm.removed) {
                String name = t.getQualifiedName() + "." + c.getName();
                ChangeItem item = item(ChangeType.DELETED, ObjectType.COLUMN, name, "dropped, its data will be lost",
                        pairKey("column", fTableId + "/" + c.getAttnum(), null));
                emit(ALTER_TABLE, item, "ALTER TABLE " + tq + " DROP COLUMN " + ident(c.getName()) + ";");
                affectedColumns.add(f.getQualifiedName() + "." + c.getName());
                affectedTables.add(f.getQualifiedName());
                warnings.add("Column " + name + " is dropped: its data will be lost.");
            }
            for (ColumnModel c : cm.added) {
                String name = t.getQualifiedName() + "." + c.getName();
                ChangeItem item = item(ChangeType.ADDED, ObjectType.COLUMN, name, "new column " + c.getDataType(),
                        pairKey("column", null, tTableId + "/" + c.getAttnum()));
                emit(ALTER_TABLE, item, "ALTER TABLE " + tq + " ADD COLUMN " + columnDefinition(c) + ";");
                if (!c.isNullable() && c.getDefaultValue() == null && c.getIdentityGeneration() == null
                        && c.getGeneratedExpression() == null) {
                    warnings.add("Column " + name + " is added as NOT NULL without DEFAULT: fails if the table has rows.");
                }
            }
            for (Matching.Pair<ColumnModel> p : cm.matched) {
                diffColumn(f, t, tq, p.from(), p.to(), fTableId + "/" + p.from().getAttnum(),
                        tTableId + "/" + p.to().getAttnum());
            }
        }

        private static List<ColumnModel> byAttnumOrder(TableModel t) {
            List<ColumnModel> list = new ArrayList<>(t.getColumns().values());
            list.sort(Comparator.comparingInt(ColumnModel::getAttnum));
            return list;
        }

        private void diffColumn(TableModel f, TableModel t, String tq, ColumnModel fc, ColumnModel tc,
                                String fromId, String toId) {
            String name = t.getQualifiedName() + "." + tc.getName();
            String col = ident(tc.getName());
            String alter = "ALTER TABLE " + tq + " ALTER COLUMN " + col + " ";

            if (!fc.getName().equals(tc.getName())) {
                ChangeItem item = item(ChangeType.RENAMED, ObjectType.COLUMN, name, "renamed from " + fc.getName(),
                        pairKey("column-rename", fromId, toId));
                emit(RENAME_COLUMN, item, "ALTER TABLE " + tq + " RENAME COLUMN " + ident(fc.getName()) + " TO " + col + ";");
            }

            List<String> details = new ArrayList<>();
            List<String> sql = new ArrayList<>();
            boolean typeChanged = !fc.getDataType().equals(tc.getDataType());
            boolean collationChanged = !Objects.equals(fc.getCollation(), tc.getCollation());
            if (fc.getIdentityGeneration() != null && tc.getIdentityGeneration() == null) {
                sql.add(alter + "DROP IDENTITY;");
                details.add("identity dropped");
            }
            if (typeChanged || collationChanged) {
                String collate = collationChanged ? " COLLATE " + (tc.getCollation() == null ? "\"default\"" : tc.getCollation()) : "";
                sql.add(alter + "TYPE " + tc.getDataType() + collate
                        + (typeChanged ? " USING " + col + "::" + tc.getDataType() : "") + ";");
                if (typeChanged) {
                    details.add("type " + fc.getDataType() + " -> " + tc.getDataType());
                    warnings.add("Column " + name + " changes type " + fc.getDataType() + " -> " + tc.getDataType()
                            + ": check the USING expression.");
                }
                if (collationChanged) {
                    details.add("collation " + fc.getCollation() + " -> " + tc.getCollation());
                }
                affectedColumns.add(f.getQualifiedName() + "." + fc.getName());
                affectedTables.add(f.getQualifiedName());
            }
            if (!Objects.equals(fc.getDefaultValue(), tc.getDefaultValue())) {
                sql.add(alter + (tc.getDefaultValue() == null ? "DROP DEFAULT;" : "SET DEFAULT " + tc.getDefaultValue() + ";"));
                details.add(tc.getDefaultValue() == null ? "default dropped" : "default " + tc.getDefaultValue());
            }
            if (fc.isNullable() != tc.isNullable()) {
                if (!tc.isNullable()) {
                    sql.add(alter + "SET NOT NULL;");
                    details.add("NOT NULL");
                } else if (tc.getIdentityGeneration() == null) {
                    sql.add(alter + "DROP NOT NULL;");
                    details.add("nullable");
                }
            }
            if (fc.getIdentityGeneration() == null && tc.getIdentityGeneration() != null) {
                sql.add(alter + "ADD GENERATED " + tc.getIdentityGeneration() + " AS IDENTITY;");
                details.add("identity " + tc.getIdentityGeneration());
            } else if (fc.getIdentityGeneration() != null && tc.getIdentityGeneration() != null
                    && !fc.getIdentityGeneration().equals(tc.getIdentityGeneration())) {
                sql.add(alter + "SET GENERATED " + tc.getIdentityGeneration() + ";");
                details.add("identity " + tc.getIdentityGeneration());
            }
            if (!Objects.equals(fc.getGeneratedExpression(), tc.getGeneratedExpression())) {
                String msg = "Generated expression of " + name + " changed: drop and re-add the column manually.";
                warnings.add(msg);
                details.add("generated expression changed (manual action required)");
                sql.add("-- MANUAL ACTION REQUIRED: " + msg);
            }
            if (!sql.isEmpty()) {
                ChangeItem item = item(ChangeType.MODIFIED, ObjectType.COLUMN, name, String.join(", ", details),
                        pairKey("column", fromId, toId));
                sql.forEach(s -> emit(ALTER_TABLE, item, s));
            }
        }

        private void diffConstraints(TableModel f, TableModel t, String fq, String tq) {
            boolean byOid = sameDb && f.getOid() == t.getOid();
            Matching<ConstraintModel> cm = Matching.of(sorted(f.getConstraints()), sorted(t.getConstraints()),
                    ConstraintModel::getOid, ConstraintModel::getName, byOid);
            for (ConstraintModel c : cm.removed) {
                ChangeItem item = item(ChangeType.DELETED, ObjectType.CONSTRAINT, t.getQualifiedName() + "." + c.getName(),
                        c.getType() + " dropped: " + c.getDefinition(), pairKey("constraint", id(c.getOid()), null));
                emit(c.getType() == ConstraintType.FOREIGN_KEY ? DROP_FOREIGN_KEY : DROP_CONSTRAINT, item,
                        "ALTER TABLE " + fq + " DROP CONSTRAINT " + ident(c.getName()) + ";");
            }
            for (ConstraintModel c : cm.added) {
                ChangeItem item = item(ChangeType.ADDED, ObjectType.CONSTRAINT, t.getQualifiedName() + "." + c.getName(),
                        c.getType() + ": " + c.getDefinition(), pairKey("constraint", null, id(c.getOid())));
                emit(c.getType() == ConstraintType.FOREIGN_KEY ? ADD_FOREIGN_KEY : ADD_CONSTRAINT, item, addConstraint(t, c));
            }
            for (Matching.Pair<ConstraintModel> p : cm.matched) {
                ConstraintModel fc = p.from();
                ConstraintModel tc = p.to();
                String name = t.getQualifiedName() + "." + tc.getName();
                boolean changed;
                if (byOid && fc.getOid() == tc.getOid() && fc.getSignature() != null && tc.getSignature() != null) {
                    changed = !fc.getSignature().equals(tc.getSignature());
                } else {
                    changed = fc.getType() != tc.getType() || !Objects.equals(fc.getDefinition(), tc.getDefinition());
                }
                if (changed) {
                    ChangeItem item = item(ChangeType.MODIFIED, ObjectType.CONSTRAINT, name,
                            fc.getDefinition() + " -> " + tc.getDefinition(),
                            pairKey("constraint", id(fc.getOid()), id(tc.getOid())));
                    emit(fc.getType() == ConstraintType.FOREIGN_KEY ? DROP_FOREIGN_KEY : DROP_CONSTRAINT, item,
                            "ALTER TABLE " + fq + " DROP CONSTRAINT " + ident(fc.getName()) + ";");
                    emit(tc.getType() == ConstraintType.FOREIGN_KEY ? ADD_FOREIGN_KEY : ADD_CONSTRAINT, item, addConstraint(t, tc));
                } else if (!fc.getName().equals(tc.getName())) {
                    ChangeItem item = item(ChangeType.RENAMED, ObjectType.CONSTRAINT, name, "renamed from " + fc.getName(),
                            pairKey("constraint-rename", id(fc.getOid()), id(tc.getOid())));
                    emit(RENAME_OTHER, item, "ALTER TABLE " + tq + " RENAME CONSTRAINT " + ident(fc.getName()) + " TO "
                            + ident(tc.getName()) + ";");
                }
            }
        }

        /** Indexes of one matched table or materialized view (index schema = schema of its table). */
        private void diffIndexes(Map<String, IndexModel> fromIndexes, Map<String, IndexModel> toIndexes,
                                 String fromSchema, String toSchema, String ownerKey, boolean byOid, int createPhase) {
            Matching<IndexModel> im = Matching.of(sorted(fromIndexes), sorted(toIndexes), IndexModel::getOid,
                    IndexModel::getName, byOid);
            for (IndexModel i : im.removed) {
                ChangeItem item = item(ChangeType.DELETED, ObjectType.INDEX, toSchema + "." + i.getName(),
                        "index on " + ownerKey + " dropped", pairKey("index", id(i.getOid()), null));
                emit(DROP_INDEX, item, "DROP INDEX " + qualified(fromSchema, i.getName()) + ";");
            }
            for (IndexModel i : im.added) {
                ChangeItem item = item(ChangeType.ADDED, ObjectType.INDEX, toSchema + "." + i.getName(),
                        "new index on " + ownerKey, pairKey("index", null, id(i.getOid())));
                emit(createPhase, item, createIndex(i));
            }
            for (Matching.Pair<IndexModel> p : im.matched) {
                IndexModel fi = p.from();
                IndexModel ti = p.to();
                boolean changed;
                if (byOid && fi.getOid() == ti.getOid() && fi.getSignature() != null && ti.getSignature() != null) {
                    changed = !fi.getSignature().equals(ti.getSignature());
                } else {
                    changed = fi.isUnique() != ti.isUnique() || !usingClause(fi).equals(usingClause(ti));
                }
                String name = toSchema + "." + ti.getName();
                if (changed) {
                    ChangeItem item = item(ChangeType.MODIFIED, ObjectType.INDEX, name, "index definition changed",
                            pairKey("index", id(fi.getOid()), id(ti.getOid())));
                    emit(DROP_INDEX, item, "DROP INDEX " + qualified(fromSchema, fi.getName()) + ";");
                    emit(createPhase, item, createIndex(ti));
                } else if (!fi.getName().equals(ti.getName())) {
                    ChangeItem item = item(ChangeType.RENAMED, ObjectType.INDEX, name, "renamed from " + fi.getName(),
                            pairKey("index-rename", id(fi.getOid()), id(ti.getOid())));
                    emit(RENAME_OTHER, item, "ALTER INDEX " + qualified(toSchema, fi.getName()) + " RENAME TO "
                            + ident(ti.getName()) + ";");
                }
            }
        }

        /** Part of an index definition after " USING " (method, columns, predicate) - independent of names. */
        private static String usingClause(IndexModel i) {
            String def = i.getDefinition();
            int idx = def == null ? -1 : def.indexOf(" USING ");
            return idx < 0 ? String.valueOf(def) : def.substring(idx + 7);
        }

        // ------------------------------------------------------------------ views

        private void diffViews() {
            Map<String, ViewModel> fromViews = from.getViews();

            // views that must be dropped: removed, changed, or reading something that changes / disappears
            Set<String> drop = new LinkedHashSet<>();
            for (ViewModel v : views.removed) {
                drop.add(v.getQualifiedName());
            }
            for (Matching.Pair<ViewModel> p : views.matched) {
                if (viewChanged(p.from(), p.to())) {
                    drop.add(p.from().getQualifiedName());
                }
            }
            for (ViewModel v : sorted(fromViews)) {
                for (ViewDependency d : v.getDependencies()) {
                    String rel = d.getRelation();
                    String reason = null;
                    if (droppedTables.contains(rel)) {
                        reason = rel + " is dropped";
                    } else if (d.getColumn() != null && affectedColumns.contains(rel + "." + d.getColumn())) {
                        reason = "column " + rel + "." + d.getColumn() + " changes";
                    } else if (d.getColumn() == null && affectedTables.contains(rel)) {
                        reason = "columns of " + rel + " change";
                    }
                    if (reason != null) {
                        viewRecreateReason.putIfAbsent(v.getQualifiedName(), reason);
                        drop.add(v.getQualifiedName());
                        break;
                    }
                }
            }
            boolean grown = true;
            while (grown) {
                grown = false;
                for (ViewModel v : sorted(fromViews)) {
                    if (drop.contains(v.getQualifiedName())) {
                        continue;
                    }
                    for (ViewDependency d : v.getDependencies()) {
                        if (drop.contains(d.getRelation())) {
                            viewRecreateReason.putIfAbsent(v.getQualifiedName(), "view " + d.getRelation() + " is recreated");
                            drop.add(v.getQualifiedName());
                            grown = true;
                            break;
                        }
                    }
                }
            }

            // one change item per affected view
            Map<String, ChangeItem> itemByFromKey = new HashMap<>();
            Map<String, ChangeItem> itemByToKey = new HashMap<>();
            Map<String, ViewModel> toCreate = new TreeMap<>();
            for (ViewModel v : views.removed) {
                itemByFromKey.put(v.getQualifiedName(), item(ChangeType.DELETED, ObjectType.VIEW, v.getQualifiedName(),
                        kind(v) + " dropped", pairKey("view", id(v.getOid()), null)));
            }
            for (Matching.Pair<ViewModel> p : views.matched) {
                ViewModel f = p.from();
                ViewModel t = p.to();
                if (drop.contains(f.getQualifiedName())) {
                    String details = viewChanged(f, t) ? kind(t) + " definition changed"
                            : kind(t) + " recreated: " + viewRecreateReason.getOrDefault(f.getQualifiedName(), "dependency changed");
                    ChangeItem item = item(ChangeType.MODIFIED, ObjectType.VIEW, t.getQualifiedName(), details,
                            pairKey("view", id(f.getOid()), id(t.getOid())));
                    itemByFromKey.put(f.getQualifiedName(), item);
                    itemByToKey.put(t.getQualifiedName(), item);
                    toCreate.put(t.getQualifiedName(), t);
                } else {
                    renameView(f, t);
                    if (t.isMaterialized()) {
                        diffIndexes(f.getIndexes(), t.getIndexes(), f.getSchemaName(), t.getSchemaName(),
                                t.getQualifiedName(), sameDb && f.getOid() == t.getOid(), CREATE_VIEW_INDEX);
                    }
                }
            }
            for (ViewModel v : views.added) {
                ChangeItem item = item(ChangeType.ADDED, ObjectType.VIEW, v.getQualifiedName(), "new " + kind(v),
                        pairKey("view", null, id(v.getOid())));
                itemByToKey.put(v.getQualifiedName(), item);
                toCreate.put(v.getQualifiedName(), v);
            }

            // DROP VIEW IF EXISTS: dependents first
            Set<String> dropped = new HashSet<>();
            for (String key : drop) {
                emitDropView(key, drop, fromViews, itemByFromKey, dropped, new HashSet<>());
            }
            // CREATE OR REPLACE VIEW: dependencies first
            Set<String> created = new HashSet<>();
            for (String key : toCreate.keySet()) {
                emitCreateView(key, toCreate, itemByToKey, created, new HashSet<>());
            }
        }

        private boolean viewChanged(ViewModel f, ViewModel t) {
            if (f.isMaterialized() != t.isMaterialized()) {
                return true;
            }
            if (sameDb && f.getOid() == t.getOid() && f.getSignature() != null && t.getSignature() != null) {
                return !f.getSignature().equals(t.getSignature());
            }
            return !normalizeViewDefinition(f.getDefinition()).equals(normalizeViewDefinition(t.getDefinition()));
        }

        private static String normalizeViewDefinition(String definition) {
            if (definition == null) {
                return "";
            }
            String s = definition.strip();
            if (s.endsWith(";")) {
                s = s.substring(0, s.length() - 1).strip();
            }
            return s;
        }

        private static String kind(ViewModel v) {
            return v.isMaterialized() ? "materialized view" : "view";
        }

        private void emitDropView(String key, Set<String> drop, Map<String, ViewModel> fromViews,
                                  Map<String, ChangeItem> items, Set<String> dropped, Set<String> visiting) {
            if (dropped.contains(key) || !visiting.add(key)) {
                return;
            }
            for (ViewModel other : sorted(fromViews)) {
                if (!drop.contains(other.getQualifiedName()) || other.getQualifiedName().equals(key)) {
                    continue;
                }
                for (ViewDependency d : other.getDependencies()) {
                    if (d.getRelation().equals(key)) {
                        emitDropView(other.getQualifiedName(), drop, fromViews, items, dropped, visiting);
                        break;
                    }
                }
            }
            ViewModel v = fromViews.get(key);
            ChangeItem item = items.get(key);
            if (v != null && item != null && dropped.add(key)) {
                emit(DROP_VIEW, item, (v.isMaterialized() ? "DROP MATERIALIZED VIEW IF EXISTS " : "DROP VIEW IF EXISTS ")
                        + qualified(v.getSchemaName(), v.getName()) + ";");
            }
        }

        private void emitCreateView(String key, Map<String, ViewModel> toCreate, Map<String, ChangeItem> items,
                                    Set<String> created, Set<String> visiting) {
            ViewModel v = toCreate.get(key);
            if (v == null || created.contains(key) || !visiting.add(key)) {
                return;
            }
            for (ViewDependency d : v.getDependencies()) {
                if (toCreate.containsKey(d.getRelation())) {
                    emitCreateView(d.getRelation(), toCreate, items, created, visiting);
                }
            }
            created.add(key);
            ChangeItem item = items.get(key);
            String body = normalizeViewDefinition(v.getDefinition());
            String q = qualified(v.getSchemaName(), v.getName());
            if (v.isMaterialized()) {
                emit(CREATE_VIEW, item, "CREATE MATERIALIZED VIEW " + q + " AS\n" + body + "\n"
                        + (v.isPopulated() ? "WITH DATA;" : "WITH NO DATA;"));
                for (IndexModel i : sorted(v.getIndexes())) {
                    emit(CREATE_VIEW_INDEX, item, createIndex(i));
                }
            } else {
                emit(CREATE_VIEW, item, "CREATE OR REPLACE VIEW " + q + " AS\n" + body + ";");
            }
        }

        private void renameView(ViewModel f, ViewModel t) {
            if (f.getSchemaName().equals(t.getSchemaName()) && f.getName().equals(t.getName())) {
                return;
            }
            String kw = t.isMaterialized() ? "ALTER MATERIALIZED VIEW " : "ALTER VIEW ";
            ChangeItem item = item(ChangeType.RENAMED, ObjectType.VIEW, t.getQualifiedName(),
                    "renamed from " + f.getQualifiedName(), pairKey("view-rename", id(f.getOid()), id(t.getOid())));
            if (!f.getSchemaName().equals(t.getSchemaName())) {
                emit(RENAME_RELATION, item, kw + qualified(f.getSchemaName(), f.getName()) + " SET SCHEMA "
                        + ident(t.getSchemaName()) + ";");
            }
            if (!f.getName().equals(t.getName())) {
                emit(RENAME_RELATION, item, kw + qualified(t.getSchemaName(), f.getName()) + " RENAME TO "
                        + ident(t.getName()) + ";");
            }
        }

        // ------------------------------------------------------------------ sequences

        private void diffSequences() {
            Matching<SequenceModel> sm = Matching.of(sorted(from.getSequences()), sorted(to.getSequences()),
                    SequenceModel::getOid, SequenceModel::getQualifiedName, sameDb);
            for (SequenceModel s : sm.removed) {
                ChangeItem item = item(ChangeType.DELETED, ObjectType.SEQUENCE, s.getQualifiedName(), "sequence dropped",
                        pairKey("sequence", id(s.getOid()), null));
                // IF EXISTS: a sequence owned by a dropped table / column is dropped together with it
                emit(DROP_SEQUENCE, item, "DROP SEQUENCE IF EXISTS " + qualified(s.getSchemaName(), s.getName()) + ";");
            }
            for (SequenceModel s : sm.added) {
                ChangeItem item = item(ChangeType.ADDED, ObjectType.SEQUENCE, s.getQualifiedName(), "new sequence",
                        pairKey("sequence", null, id(s.getOid())));
                emit(CREATE_SEQUENCE, item, "CREATE SEQUENCE " + qualified(s.getSchemaName(), s.getName())
                        + sequenceOptions(s) + ";");
                if (s.getOwnedByTable() != null) {
                    emit(SEQUENCE_OWNERSHIP, item, "ALTER SEQUENCE " + qualified(s.getSchemaName(), s.getName())
                            + " OWNED BY " + ownedBy(s) + ";");
                }
            }
            for (Matching.Pair<SequenceModel> p : sm.matched) {
                SequenceModel f = p.from();
                SequenceModel t = p.to();
                String fromId = id(f.getOid());
                String toId = id(t.getOid());
                String tq = qualified(t.getSchemaName(), t.getName());
                if (!f.getSchemaName().equals(t.getSchemaName()) || !f.getName().equals(t.getName())) {
                    ChangeItem item = item(ChangeType.RENAMED, ObjectType.SEQUENCE, t.getQualifiedName(),
                            "renamed from " + f.getQualifiedName(), pairKey("sequence-rename", fromId, toId));
                    if (!f.getSchemaName().equals(t.getSchemaName())) {
                        emit(RENAME_RELATION, item, "ALTER SEQUENCE " + qualified(f.getSchemaName(), f.getName())
                                + " SET SCHEMA " + ident(t.getSchemaName()) + ";");
                    }
                    if (!f.getName().equals(t.getName())) {
                        emit(RENAME_RELATION, item, "ALTER SEQUENCE " + qualified(t.getSchemaName(), f.getName())
                                + " RENAME TO " + ident(t.getName()) + ";");
                    }
                }
                List<String> details = new ArrayList<>();
                List<String[]> sql = new ArrayList<>();
                if (!sameOptions(f, t)) {
                    details.add("options changed");
                    sql.add(new String[]{Integer.toString(ALTER_SEQUENCE), "ALTER SEQUENCE " + tq + sequenceOptions(t) + ";"});
                }
                String fromOwner = f.getOwnedByTable() == null ? null : mapRelation(f.getOwnedByTable()) + "." + f.getOwnedByColumn();
                String toOwner = t.getOwnedByTable() == null ? null : t.getOwnedByTable() + "." + t.getOwnedByColumn();
                if (!Objects.equals(fromOwner, toOwner)) {
                    details.add(toOwner == null ? "no longer owned by a column" : "owned by " + toOwner);
                    if (f.getOwnedByTable() != null) {
                        // release first, so dropping the old owner does not drop the sequence
                        sql.add(new String[]{Integer.toString(RELEASE_OWNERSHIP),
                                "ALTER SEQUENCE " + qualified(f.getSchemaName(), f.getName()) + " OWNED BY NONE;"});
                    }
                    if (t.getOwnedByTable() != null) {
                        sql.add(new String[]{Integer.toString(SEQUENCE_OWNERSHIP),
                                "ALTER SEQUENCE " + tq + " OWNED BY " + ownedBy(t) + ";"});
                    }
                }
                if (!sql.isEmpty()) {
                    ChangeItem item = item(ChangeType.MODIFIED, ObjectType.SEQUENCE, t.getQualifiedName(),
                            String.join(", ", details), pairKey("sequence", fromId, toId));
                    sql.forEach(s -> emit(Integer.parseInt(s[0]), item, s[1]));
                }
            }
        }

        private static boolean sameOptions(SequenceModel a, SequenceModel b) {
            return Objects.equals(a.getDataType(), b.getDataType()) && a.getStartValue() == b.getStartValue()
                    && a.getIncrementBy() == b.getIncrementBy() && a.getMinValue() == b.getMinValue()
                    && a.getMaxValue() == b.getMaxValue() && a.getCacheSize() == b.getCacheSize()
                    && a.isCycle() == b.isCycle();
        }

        private static String sequenceOptions(SequenceModel s) {
            return "\n    AS " + s.getDataType()
                    + "\n    INCREMENT BY " + s.getIncrementBy()
                    + "\n    MINVALUE " + s.getMinValue()
                    + "\n    MAXVALUE " + s.getMaxValue()
                    + "\n    START WITH " + s.getStartValue()
                    + "\n    CACHE " + s.getCacheSize()
                    + "\n    " + (s.isCycle() ? "CYCLE" : "NO CYCLE");
        }

        private static String ownedBy(SequenceModel s) {
            return qualifiedKey(s.getOwnedByTable()) + "." + ident(s.getOwnedByColumn());
        }
    }
}
