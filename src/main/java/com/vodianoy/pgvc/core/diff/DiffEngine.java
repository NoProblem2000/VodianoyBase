package com.vodianoy.pgvc.core.diff;

import com.vodianoy.pgvc.core.model.ColumnDef;
import com.vodianoy.pgvc.core.model.ConstraintDef;
import com.vodianoy.pgvc.core.model.DatabaseSnapshot;
import com.vodianoy.pgvc.core.model.IndexDef;
import com.vodianoy.pgvc.core.model.OwnedBy;
import com.vodianoy.pgvc.core.model.SequenceDef;
import com.vodianoy.pgvc.core.model.TableDef;
import com.vodianoy.pgvc.core.model.ViewDef;
import com.vodianoy.pgvc.core.model.ViewDependency;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.vodianoy.pgvc.core.util.Sql.ident;
import static com.vodianoy.pgvc.core.util.Sql.qn;

/**
 * Compares two {@link DatabaseSnapshot}s and produces DDL that transforms "from" into "to".
 *
 * <p>Statements are emitted in a dependency-safe order:
 * <ol>
 *   <li>create new schemas</li>
 *   <li>drop views that are removed, changed or depend on columns/tables being changed or dropped
 *       (dependents first)</li>
 *   <li>release sequence ownership, drop foreign keys, other constraints, indexes</li>
 *   <li>detach partitions, drop tables</li>
 *   <li>renames (tables, sequences, views, columns, constraints, indexes)</li>
 *   <li>drop / create / alter sequences</li>
 *   <li>create tables (partition parents first), alter columns, attach partitions</li>
 *   <li>add constraints (FK last), create indexes, sequence ownership</li>
 *   <li>(re)create views (dependencies first) and indexes of materialized views</li>
 *   <li>drop removed schemas</li>
 * </ol>
 * So e.g. a column type change that a view depends on yields DROP VIEW, ALTER TABLE, CREATE VIEW.
 */
public final class DiffEngine {

    public DiffResult diff(DatabaseSnapshot from, DatabaseSnapshot to) {
        return new Run(from == null ? DatabaseSnapshot.empty() : from, to == null ? DatabaseSnapshot.empty() : to)
                .execute();
    }

    private static final class Run {

        private static final String DROPPED = "\u0000dropped:";

        private final DatabaseSnapshot from;
        private final DatabaseSnapshot to;
        private final boolean sameDb;

        private final List<String> warnings = new ArrayList<>();
        private final List<String> summary = new ArrayList<>();

        private final List<String> createSchemas = new ArrayList<>();
        private final List<String> dropViews = new ArrayList<>();
        private final List<String> releaseOwnership = new ArrayList<>();
        private final List<String> dropFks = new ArrayList<>();
        private final List<String> dropConstraints = new ArrayList<>();
        private final List<String> dropIndexes = new ArrayList<>();
        private final List<String> detach = new ArrayList<>();
        private final List<String> dropTables = new ArrayList<>();
        private final List<String> renameRelations = new ArrayList<>();
        private final List<String> renameColumns = new ArrayList<>();
        private final List<String> renameOther = new ArrayList<>();
        private final List<String> dropSequences = new ArrayList<>();
        private final List<String> createSequences = new ArrayList<>();
        private final List<String> alterSequences = new ArrayList<>();
        private final List<String> createTables = new ArrayList<>();
        private final List<String> alterTables = new ArrayList<>();
        private final List<String> attach = new ArrayList<>();
        private final List<String> addConstraints = new ArrayList<>();
        private final List<String> addFks = new ArrayList<>();
        private final List<String> createIndexes = new ArrayList<>();
        private final List<String> sequenceOwnership = new ArrayList<>();
        private final List<String> createViews = new ArrayList<>();
        private final List<String> createViewIndexes = new ArrayList<>();
        private final List<String> dropSchemas = new ArrayList<>();

        /** from-key of relation (table or view) -> to-key, or DROPPED marker. */
        private final Map<String, String> relationMapping = new HashMap<>();
        /** from-keys of tables that are dropped. */
        private final Set<String> droppedTables = new HashSet<>();
        /** "schema.table.column" (from names) whose type changes or that are dropped. */
        private final Set<String> affectedColumns = new HashSet<>();
        /** from-keys of tables having at least one affected column. */
        private final Set<String> affectedTables = new HashSet<>();
        /** from-keys of views that are dropped (removed or recreated). */
        private final Set<String> droppedViews = new LinkedHashSet<>();
        /** to-keys of views (for classifying indexes on materialized views). */
        private final Set<String> toViewKeys = new HashSet<>();

        private Matching<TableDef> tables;
        private Matching<ViewDef> views;

        Run(DatabaseSnapshot from, DatabaseSnapshot to) {
            this.from = from;
            this.to = to;
            this.sameDb = from.sourceDatabaseId() != null && !from.sourceDatabaseId().startsWith("unknown")
                    && from.sourceDatabaseId().equals(to.sourceDatabaseId());
        }

        DiffResult execute() {
            tables = Matching.of(from.tables(), to.tables(), TableDef::oid, TableDef::key, sameDb);
            views = Matching.of(from.views(), to.views(), ViewDef::oid, ViewDef::key, sameDb);
            for (Matching.Pair<TableDef> p : tables.matched) {
                relationMapping.put(p.from().key(), p.to().key());
            }
            for (TableDef t : tables.removed) {
                relationMapping.put(t.key(), DROPPED + t.key());
                droppedTables.add(t.key());
            }
            for (Matching.Pair<ViewDef> p : views.matched) {
                relationMapping.put(p.from().key(), p.to().key());
            }
            for (ViewDef v : views.removed) {
                relationMapping.put(v.key(), DROPPED + v.key());
            }
            for (ViewDef v : to.views()) {
                toViewKeys.add(v.key());
            }

            diffSchemas();
            diffTables();
            diffViews();
            diffIndexes();
            diffSequences();
            dropSchemasLast();

            List<String> statements = new ArrayList<>();
            statements.addAll(createSchemas);
            statements.addAll(dropViews);
            statements.addAll(releaseOwnership);
            statements.addAll(dropFks);
            statements.addAll(dropConstraints);
            statements.addAll(dropIndexes);
            statements.addAll(detach);
            statements.addAll(dropTables);
            statements.addAll(renameRelations);
            statements.addAll(renameColumns);
            statements.addAll(renameOther);
            statements.addAll(dropSequences);
            statements.addAll(createSequences);
            statements.addAll(alterSequences);
            statements.addAll(createTables);
            statements.addAll(alterTables);
            statements.addAll(attach);
            statements.addAll(addConstraints);
            statements.addAll(addFks);
            statements.addAll(createIndexes);
            statements.addAll(sequenceOwnership);
            statements.addAll(createViews);
            statements.addAll(createViewIndexes);
            statements.addAll(dropSchemas);
            return new DiffResult(statements, warnings, summary);
        }

        private String mapRelation(String fromKey) {
            String mapped = relationMapping.get(fromKey);
            return mapped == null ? fromKey : mapped;
        }

        // ------------------------------------------------------------------ schemas

        private void diffSchemas() {
            Set<String> fromSchemas = new HashSet<>(from.schemas());
            for (String s : to.schemas()) {
                if (!fromSchemas.contains(s) && !"public".equals(s)) {
                    createSchemas.add("CREATE SCHEMA IF NOT EXISTS " + ident(s) + ";");
                    summary.add("+ schema " + s);
                }
            }
        }

        private void dropSchemasLast() {
            Set<String> toSchemas = new HashSet<>(to.schemas());
            for (String s : from.schemas()) {
                if (!toSchemas.contains(s) && !"public".equals(s)) {
                    dropSchemas.add("DROP SCHEMA " + ident(s) + ";");
                    summary.add("- schema " + s);
                }
            }
        }

        // ------------------------------------------------------------------ tables

        private void diffTables() {
            for (TableDef f : tables.removed) {
                summary.add("- table " + f.key());
                warnings.add("Table " + f.key() + " is dropped: its data will be lost.");
                for (ConstraintDef c : f.constraints()) {
                    if (c.foreignKey()) {
                        dropFks.add("ALTER TABLE " + qn(f.schema(), f.name()) + " DROP CONSTRAINT " + ident(c.name()) + ";");
                    }
                }
                boolean parentAlsoDropped = f.partition() && droppedTables.contains(f.parentKey());
                if (!parentAlsoDropped) {
                    dropTables.add("DROP TABLE " + qn(f.schema(), f.name()) + ";");
                }
            }

            List<TableDef> added = new ArrayList<>(tables.added);
            Map<String, TableDef> toByKey = new HashMap<>();
            for (TableDef t : to.tables()) {
                toByKey.put(t.key(), t);
            }
            added.sort(Comparator.comparingInt((TableDef t) -> depth(t, toByKey)).thenComparing(TableDef::key));
            for (TableDef t : added) {
                summary.add("+ table " + t.key());
                createTables.add(createTable(t));
                for (ConstraintDef c : t.constraints()) {
                    addConstraint(t, c);
                }
            }

            for (Matching.Pair<TableDef> p : tables.matched) {
                diffTable(p.from(), p.to());
            }
        }

        private int depth(TableDef t, Map<String, TableDef> byKey) {
            int d = 0;
            TableDef cur = t;
            Set<String> seen = new HashSet<>();
            while (cur != null && cur.partition() && seen.add(cur.key())) {
                d++;
                cur = byKey.get(cur.parentKey());
            }
            return d;
        }

        private String createTable(TableDef t) {
            StringBuilder sb = new StringBuilder("CREATE TABLE ").append(qn(t.schema(), t.name()));
            if (t.partition()) {
                sb.append(" PARTITION OF ").append(qn(t.parentSchema(), t.parentTable()))
                        .append("\n    ").append(t.partitionBound());
            } else {
                sb.append(" (");
                for (int i = 0; i < t.columns().size(); i++) {
                    sb.append(i == 0 ? "\n" : ",\n").append("    ").append(columnDefinition(t.columns().get(i)));
                }
                sb.append(t.columns().isEmpty() ? ")" : "\n)");
            }
            if (t.partitioned()) {
                sb.append("\n    PARTITION BY ").append(t.partitionKey());
            }
            return sb.append(';').toString();
        }

        private String columnDefinition(ColumnDef c) {
            StringBuilder sb = new StringBuilder(ident(c.name())).append(' ').append(c.type());
            if ("a".equals(c.identity())) {
                sb.append(" GENERATED ALWAYS AS IDENTITY");
            } else if ("d".equals(c.identity())) {
                sb.append(" GENERATED BY DEFAULT AS IDENTITY");
            }
            if (c.defaultExpr() != null) {
                sb.append(" DEFAULT ").append(c.defaultExpr());
            }
            if (c.notNull() && c.identity() == null) {
                sb.append(" NOT NULL");
            }
            return sb.toString();
        }

        private void addConstraint(TableDef t, ConstraintDef c) {
            String sql = "ALTER TABLE " + qn(t.schema(), t.name()) + " ADD CONSTRAINT " + ident(c.name()) + " "
                    + c.definition() + ";";
            (c.foreignKey() ? addFks : addConstraints).add(sql);
        }

        private void diffTable(TableDef f, TableDef t) {
            String fq = qn(f.schema(), f.name());
            String tq = qn(t.schema(), t.name());

            // --- rename / move (detected by identical pg_class.oid)
            if (!f.schema().equals(t.schema())) {
                renameRelations.add("ALTER TABLE " + fq + " SET SCHEMA " + ident(t.schema()) + ";");
                summary.add("~ table " + f.key() + " moved to schema " + t.schema());
            }
            if (!f.name().equals(t.name())) {
                renameRelations.add("ALTER TABLE " + qn(t.schema(), f.name()) + " RENAME TO " + ident(t.name()) + ";");
                summary.add("~ table " + f.key() + " renamed to " + t.key());
            }

            // --- partitioning
            if (f.partitioned() != t.partitioned() || !Objects.equals(f.partitionKey(), t.partitionKey())) {
                String msg = "Partitioning of " + t.key() + " changed (" + describePartitioning(f) + " -> "
                        + describePartitioning(t) + "). PostgreSQL cannot change it in place: recreate the table manually.";
                warnings.add(msg);
                summary.add("! table " + t.key() + " partitioning changed");
            }
            String fromParentAsTo = f.partition() ? mapRelation(f.parentKey()) : null;
            boolean membershipChanged = !Objects.equals(fromParentAsTo, t.parentKey())
                    || !Objects.equals(f.partitionBound(), t.partitionBound());
            if (membershipChanged) {
                if (f.partition()) {
                    detach.add("ALTER TABLE " + qn(f.parentSchema(), f.parentTable()) + " DETACH PARTITION " + fq + ";");
                    summary.add("~ partition " + f.key() + " detached from " + f.parentKey());
                }
                if (t.partition()) {
                    attach.add("ALTER TABLE " + qn(t.parentSchema(), t.parentTable()) + " ATTACH PARTITION " + tq + "\n    "
                            + t.partitionBound() + ";");
                    summary.add("~ partition " + t.key() + " attached to " + t.parentKey() + " " + t.partitionBound());
                }
            }

            // --- columns (partition columns always follow the parent)
            if (!(f.partition() && t.partition())) {
                diffColumns(f, t, tq);
            }

            // --- constraints
            boolean byOid = sameDb && f.oid() == t.oid();
            Matching<ConstraintDef> cm = Matching.of(f.constraints(), t.constraints(), ConstraintDef::oid,
                    ConstraintDef::name, byOid);
            for (ConstraintDef c : cm.removed) {
                (c.foreignKey() ? dropFks : dropConstraints).add("ALTER TABLE " + fq + " DROP CONSTRAINT " + ident(c.name()) + ";");
                summary.add("- constraint " + c.name() + " on " + t.key());
            }
            for (Matching.Pair<ConstraintDef> p : cm.matched) {
                ConstraintDef fc = p.from();
                ConstraintDef tc = p.to();
                boolean changed;
                if (byOid && fc.oid() == tc.oid() && fc.signature() != null && tc.signature() != null) {
                    changed = !fc.signature().equals(tc.signature());
                } else {
                    changed = !fc.type().equals(tc.type()) || !Objects.equals(fc.definition(), tc.definition());
                }
                if (changed) {
                    (fc.foreignKey() ? dropFks : dropConstraints).add("ALTER TABLE " + fq + " DROP CONSTRAINT " + ident(fc.name()) + ";");
                    addConstraint(t, tc);
                    summary.add("~ constraint " + tc.name() + " on " + t.key() + " changed");
                } else if (!fc.name().equals(tc.name())) {
                    renameOther.add("ALTER TABLE " + tq + " RENAME CONSTRAINT " + ident(fc.name()) + " TO " + ident(tc.name()) + ";");
                    summary.add("~ constraint " + fc.name() + " on " + t.key() + " renamed to " + tc.name());
                }
            }
            for (ConstraintDef c : cm.added) {
                addConstraint(t, c);
                summary.add("+ constraint " + c.name() + " on " + t.key());
            }
        }

        private String describePartitioning(TableDef t) {
            return t.partitioned() ? "PARTITION BY " + t.partitionKey() : "not partitioned";
        }

        private void diffColumns(TableDef f, TableDef t, String tq) {
            boolean byAttnum = sameDb && f.oid() == t.oid();
            Matching<ColumnDef> cm = Matching.of(f.columns(), t.columns(), c -> (long) c.attnum(), ColumnDef::name, byAttnum);
            List<String> drops = new ArrayList<>();
            List<String> adds = new ArrayList<>();
            List<String> alters = new ArrayList<>();

            for (ColumnDef c : cm.removed) {
                drops.add("ALTER TABLE " + tq + " DROP COLUMN " + ident(c.name()) + ";");
                affectedColumns.add(f.key() + "." + c.name());
                affectedTables.add(f.key());
                summary.add("- column " + t.key() + "." + c.name());
                warnings.add("Column " + t.key() + "." + c.name() + " is dropped: its data will be lost.");
            }
            for (Matching.Pair<ColumnDef> p : cm.matched) {
                ColumnDef fc = p.from();
                ColumnDef tc = p.to();
                String col = ident(tc.name());
                String alter = "ALTER TABLE " + tq + " ALTER COLUMN " + col + " ";
                if (!fc.name().equals(tc.name())) {
                    renameColumns.add("ALTER TABLE " + tq + " RENAME COLUMN " + ident(fc.name()) + " TO " + col + ";");
                    summary.add("~ column " + t.key() + "." + fc.name() + " renamed to " + tc.name());
                }
                if (!fc.type().equals(tc.type())) {
                    alters.add(alter + "TYPE " + tc.type() + " USING " + col + "::" + tc.type() + ";");
                    affectedColumns.add(f.key() + "." + fc.name());
                    affectedTables.add(f.key());
                    summary.add("~ column " + t.key() + "." + tc.name() + " type " + fc.type() + " -> " + tc.type());
                    warnings.add("Column " + t.key() + "." + tc.name() + " changes type " + fc.type() + " -> " + tc.type()
                            + ": check the USING expression.");
                }
                if (fc.identity() != null && tc.identity() == null) {
                    alters.add(alter + "DROP IDENTITY;");
                    summary.add("~ column " + t.key() + "." + tc.name() + " identity dropped");
                }
                if (!Objects.equals(fc.defaultExpr(), tc.defaultExpr())) {
                    alters.add(alter + (tc.defaultExpr() == null ? "DROP DEFAULT;" : "SET DEFAULT " + tc.defaultExpr() + ";"));
                    summary.add("~ column " + t.key() + "." + tc.name() + " default "
                            + (tc.defaultExpr() == null ? "dropped" : "= " + tc.defaultExpr()));
                }
                if (fc.notNull() != tc.notNull()) {
                    if (tc.notNull()) {
                        alters.add(alter + "SET NOT NULL;");
                        summary.add("~ column " + t.key() + "." + tc.name() + " SET NOT NULL");
                    } else if (tc.identity() == null) {
                        alters.add(alter + "DROP NOT NULL;");
                        summary.add("~ column " + t.key() + "." + tc.name() + " DROP NOT NULL");
                    }
                }
                if (fc.identity() == null && tc.identity() != null) {
                    alters.add(alter + "ADD " + identityClause(tc.identity()) + ";");
                    summary.add("~ column " + t.key() + "." + tc.name() + " identity added");
                } else if (fc.identity() != null && tc.identity() != null && !fc.identity().equals(tc.identity())) {
                    alters.add(alter + "SET " + ("a".equals(tc.identity()) ? "GENERATED ALWAYS" : "GENERATED BY DEFAULT") + ";");
                    summary.add("~ column " + t.key() + "." + tc.name() + " identity mode changed");
                }
            }
            for (ColumnDef c : cm.added) {
                adds.add("ALTER TABLE " + tq + " ADD COLUMN " + columnDefinition(c) + ";");
                summary.add("+ column " + t.key() + "." + c.name());
                if (c.notNull() && c.defaultExpr() == null && c.identity() == null) {
                    warnings.add("Column " + t.key() + "." + c.name()
                            + " is added as NOT NULL without DEFAULT: fails if the table has rows.");
                }
            }
            alterTables.addAll(drops);
            alterTables.addAll(adds);
            alterTables.addAll(alters);
        }

        private String identityClause(String identity) {
            return "a".equals(identity) ? "GENERATED ALWAYS AS IDENTITY" : "GENERATED BY DEFAULT AS IDENTITY";
        }

        // ------------------------------------------------------------------ views

        private void diffViews() {
            Map<String, ViewDef> fromViews = new HashMap<>();
            for (ViewDef v : from.views()) {
                fromViews.put(v.key(), v);
            }

            Set<String> drop = new LinkedHashSet<>();
            for (ViewDef v : views.removed) {
                drop.add(v.key());
            }
            for (Matching.Pair<ViewDef> p : views.matched) {
                if (viewChanged(p.from(), p.to())) {
                    drop.add(p.from().key());
                }
            }
            for (ViewDef v : from.views()) {
                for (ViewDependency d : v.dependencies()) {
                    String rel = d.relationKey();
                    if (droppedTables.contains(rel)
                            || (d.column() != null && affectedColumns.contains(rel + "." + d.column()))
                            || (d.column() == null && affectedTables.contains(rel))) {
                        drop.add(v.key());
                        break;
                    }
                }
            }
            boolean grown = true;
            while (grown) {
                grown = false;
                for (ViewDef v : from.views()) {
                    if (drop.contains(v.key())) {
                        continue;
                    }
                    for (ViewDependency d : v.dependencies()) {
                        if (drop.contains(d.relationKey())) {
                            drop.add(v.key());
                            grown = true;
                            break;
                        }
                    }
                }
            }
            droppedViews.addAll(drop);

            // drop: dependents first
            Set<String> emitted = new HashSet<>();
            for (String key : drop) {
                emitDropView(key, drop, fromViews, emitted, new HashSet<>());
            }

            // recreate / create: dependencies first
            Map<String, ViewDef> toCreate = new HashMap<>();
            for (Matching.Pair<ViewDef> p : views.matched) {
                if (drop.contains(p.from().key())) {
                    toCreate.put(p.to().key(), p.to());
                    boolean changed = viewChanged(p.from(), p.to());
                    summary.add((changed ? "~ view " : "~ view (recreated for dependency) ") + p.to().key());
                } else {
                    renameView(p.from(), p.to());
                }
            }
            for (ViewDef v : views.removed) {
                summary.add("- " + kind(v) + " " + v.key());
            }
            for (ViewDef v : views.added) {
                toCreate.put(v.key(), v);
                summary.add("+ " + kind(v) + " " + v.key());
            }
            Set<String> created = new HashSet<>();
            List<String> keys = new ArrayList<>(toCreate.keySet());
            keys.sort(null);
            for (String key : keys) {
                emitCreateView(key, toCreate, created, new HashSet<>());
            }
        }

        private boolean viewChanged(ViewDef f, ViewDef t) {
            if (f.materialized() != t.materialized()) {
                return true;
            }
            if (sameDb && f.oid() == t.oid() && f.signature() != null && t.signature() != null) {
                return !f.signature().equals(t.signature());
            }
            return !normalizeViewDef(f.definition()).equals(normalizeViewDef(t.definition()));
        }

        private String normalizeViewDef(String def) {
            if (def == null) {
                return "";
            }
            String s = def.strip();
            if (s.endsWith(";")) {
                s = s.substring(0, s.length() - 1).strip();
            }
            return s;
        }

        private String kind(ViewDef v) {
            return v.materialized() ? "materialized view" : "view";
        }

        private void emitDropView(String key, Set<String> drop, Map<String, ViewDef> fromViews, Set<String> emitted,
                                  Set<String> visiting) {
            if (emitted.contains(key) || !visiting.add(key)) {
                return;
            }
            for (ViewDef other : from.views()) {
                if (!drop.contains(other.key()) || other.key().equals(key)) {
                    continue;
                }
                for (ViewDependency d : other.dependencies()) {
                    if (d.relationKey().equals(key)) {
                        emitDropView(other.key(), drop, fromViews, emitted, visiting);
                        break;
                    }
                }
            }
            ViewDef v = fromViews.get(key);
            if (v != null && emitted.add(key)) {
                dropViews.add("DROP " + (v.materialized() ? "MATERIALIZED VIEW " : "VIEW ") + qn(v.schema(), v.name()) + ";");
            }
        }

        private void emitCreateView(String key, Map<String, ViewDef> toCreate, Set<String> created, Set<String> visiting) {
            ViewDef v = toCreate.get(key);
            if (v == null || created.contains(key) || !visiting.add(key)) {
                return;
            }
            for (ViewDependency d : v.dependencies()) {
                if (toCreate.containsKey(d.relationKey())) {
                    emitCreateView(d.relationKey(), toCreate, created, visiting);
                }
            }
            created.add(key);
            String body = normalizeViewDef(v.definition());
            if (v.materialized()) {
                createViews.add("CREATE MATERIALIZED VIEW " + qn(v.schema(), v.name()) + " AS\n" + body + "\nWITH DATA;");
            } else {
                createViews.add("CREATE VIEW " + qn(v.schema(), v.name()) + " AS\n" + body + ";");
            }
        }

        private void renameView(ViewDef f, ViewDef t) {
            String kw = t.materialized() ? "ALTER MATERIALIZED VIEW " : "ALTER VIEW ";
            if (!f.schema().equals(t.schema())) {
                renameRelations.add(kw + qn(f.schema(), f.name()) + " SET SCHEMA " + ident(t.schema()) + ";");
                summary.add("~ " + kind(t) + " " + f.key() + " moved to schema " + t.schema());
            }
            if (!f.name().equals(t.name())) {
                renameRelations.add(kw + qn(t.schema(), f.name()) + " RENAME TO " + ident(t.name()) + ";");
                summary.add("~ " + kind(t) + " " + f.key() + " renamed to " + t.key());
            }
        }

        // ------------------------------------------------------------------ indexes

        private void diffIndexes() {
            Matching<IndexDef> im = Matching.of(from.indexes(), to.indexes(), IndexDef::oid, IndexDef::key, sameDb);
            for (IndexDef i : im.removed) {
                summary.add("- index " + i.key());
                if (droppedTables.contains(i.tableKey()) || droppedViews.contains(i.tableKey())) {
                    continue;
                }
                dropIndexes.add("DROP INDEX " + qn(i.schema(), i.name()) + ";");
            }
            for (IndexDef i : im.added) {
                summary.add("+ index " + i.key());
                createIndex(i);
            }
            for (Matching.Pair<IndexDef> p : im.matched) {
                IndexDef fi = p.from();
                IndexDef ti = p.to();
                if (droppedViews.contains(fi.tableKey())) {
                    // the materialized view is recreated: its indexes must be recreated too
                    createIndex(ti);
                    continue;
                }
                boolean changed;
                if (sameDb && fi.oid() == ti.oid() && fi.signature() != null && ti.signature() != null) {
                    changed = !fi.signature().equals(ti.signature()) || fi.tableOid() != ti.tableOid();
                } else {
                    changed = !mapRelation(fi.tableKey()).equals(ti.tableKey()) || fi.unique() != ti.unique()
                            || !fi.usingClause().equals(ti.usingClause());
                }
                if (changed) {
                    dropIndexes.add("DROP INDEX " + qn(fi.schema(), fi.name()) + ";");
                    createIndex(ti);
                    summary.add("~ index " + ti.key() + " changed");
                } else if (!fi.name().equals(ti.name())) {
                    renameOther.add("ALTER INDEX " + qn(ti.schema(), fi.name()) + " RENAME TO " + ident(ti.name()) + ";");
                    summary.add("~ index " + fi.key() + " renamed to " + ti.key());
                }
            }
        }

        private void createIndex(IndexDef i) {
            String def = i.definition().strip();
            if (!def.endsWith(";")) {
                def = def + ";";
            }
            (toViewKeys.contains(i.tableKey()) ? createViewIndexes : createIndexes).add(def);
        }

        // ------------------------------------------------------------------ sequences

        private void diffSequences() {
            Matching<SequenceDef> sm = Matching.of(from.sequences(), to.sequences(), SequenceDef::oid, SequenceDef::key, sameDb);
            for (SequenceDef s : sm.removed) {
                dropSequences.add("DROP SEQUENCE IF EXISTS " + qn(s.schema(), s.name()) + ";");
                summary.add("- sequence " + s.key());
            }
            for (SequenceDef s : sm.added) {
                createSequences.add("CREATE SEQUENCE " + qn(s.schema(), s.name()) + sequenceOptions(s) + ";");
                summary.add("+ sequence " + s.key());
                if (s.ownedBy() != null) {
                    sequenceOwnership.add("ALTER SEQUENCE " + qn(s.schema(), s.name()) + " OWNED BY " + ownedBy(s.ownedBy()) + ";");
                }
            }
            for (Matching.Pair<SequenceDef> p : sm.matched) {
                SequenceDef f = p.from();
                SequenceDef t = p.to();
                String tq = qn(t.schema(), t.name());
                if (!f.schema().equals(t.schema())) {
                    renameRelations.add("ALTER SEQUENCE " + qn(f.schema(), f.name()) + " SET SCHEMA " + ident(t.schema()) + ";");
                    summary.add("~ sequence " + f.key() + " moved to schema " + t.schema());
                }
                if (!f.name().equals(t.name())) {
                    renameRelations.add("ALTER SEQUENCE " + qn(t.schema(), f.name()) + " RENAME TO " + ident(t.name()) + ";");
                    summary.add("~ sequence " + f.key() + " renamed to " + t.key());
                }
                if (!f.sameOptions(t)) {
                    alterSequences.add("ALTER SEQUENCE " + tq + sequenceOptions(t) + ";");
                    summary.add("~ sequence " + t.key() + " options changed");
                }
                String fromOwner = f.ownedBy() == null ? null
                        : mapRelation(f.ownedBy().schema() + "." + f.ownedBy().table()) + "." + f.ownedBy().column();
                String toOwner = t.ownedBy() == null ? null
                        : t.ownedBy().schema() + "." + t.ownedBy().table() + "." + t.ownedBy().column();
                if (!Objects.equals(fromOwner, toOwner)) {
                    if (f.ownedBy() != null) {
                        // release first so dropping the old owner table/column does not drop the sequence
                        releaseOwnership.add("ALTER SEQUENCE " + qn(f.schema(), f.name()) + " OWNED BY NONE;");
                    }
                    if (t.ownedBy() != null) {
                        sequenceOwnership.add("ALTER SEQUENCE " + tq + " OWNED BY " + ownedBy(t.ownedBy()) + ";");
                    }
                    summary.add("~ sequence " + t.key() + " owner " + (toOwner == null ? "removed" : "= " + toOwner));
                }
            }
        }

        private String sequenceOptions(SequenceDef s) {
            return "\n    AS " + s.dataType()
                    + "\n    INCREMENT BY " + s.increment()
                    + "\n    MINVALUE " + s.min()
                    + "\n    MAXVALUE " + s.max()
                    + "\n    START WITH " + s.start()
                    + "\n    CACHE " + s.cache()
                    + "\n    " + (s.cycle() ? "CYCLE" : "NO CYCLE");
        }

        private String ownedBy(OwnedBy o) {
            return qn(o.schema(), o.table()) + "." + ident(o.column());
        }
    }
}
