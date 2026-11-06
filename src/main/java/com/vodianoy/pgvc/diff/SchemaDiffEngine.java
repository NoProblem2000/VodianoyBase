package com.vodianoy.pgvc.diff;

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
import com.vodianoy.pgvc.settings.DiffSettingsService;
import com.vodianoy.pgvc.util.SqlIdentifiers;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.vodianoy.pgvc.util.SqlIdentifiers.ident;
import static com.vodianoy.pgvc.util.SqlIdentifiers.literal;
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
 * before their partitions; foreign keys are dropped before tables and added after all other constraints. Extensions
 * and ENUM types come before tables, functions before tables (or right after them when their signature uses a new
 * table), triggers after tables, functions and views; functions, types and extensions are dropped at the end.
 * {@code ALTER TYPE ... ADD VALUE} goes first, in its own step outside of a transaction ({@code -- pgvc:step}).
 *
 * <p>Both snapshots are filtered with the {@link DiffIgnoreRules} first: the rules passed to a call, otherwise the
 * saved default rules.
 */
@Component
public class SchemaDiffEngine {

    private final Supplier<DiffIgnoreRules> defaultRules;

    /** Engine with the built-in default rules ({@link DiffIgnoreRules#DEFAULTS}). */
    public SchemaDiffEngine() {
        this(() -> DiffIgnoreRules.DEFAULTS);
    }

    /** Engine that uses the saved default rules of the installation. */
    @Autowired
    public SchemaDiffEngine(DiffSettingsService settings) {
        this(settings::getIgnoreRules);
    }

    public SchemaDiffEngine(Supplier<DiffIgnoreRules> defaultRules) {
        this.defaultRules = defaultRules;
    }

    /** Rules used when a call passes none. */
    public DiffIgnoreRules defaultRules() {
        DiffIgnoreRules rules = defaultRules.get();
        return rules == null ? DiffIgnoreRules.DEFAULTS : rules;
    }

    /** Working tree: HEAD -> live database; the changes already happened ({@link DiffMode#APPLIED}). */
    public SchemaDiffResult diff(DatabaseSnapshot headSnapshot, DatabaseSnapshot liveSnapshot) {
        return diff(headSnapshot, liveSnapshot, null);
    }

    /** @param rules null: the default rules */
    public SchemaDiffResult diff(DatabaseSnapshot headSnapshot, DatabaseSnapshot liveSnapshot, DiffIgnoreRules rules) {
        return diff(headSnapshot, liveSnapshot, rules, DiffMode.APPLIED);
    }

    /** Same database, before -> after, worded for {@code mode} (history: {@link DiffMode#RECORDED}). */
    public SchemaDiffResult diff(DatabaseSnapshot before, DatabaseSnapshot after, DiffIgnoreRules rules, DiffMode mode) {
        return run(before, after, false, false, rules, mode);
    }

    /**
     * Comparison of two different environments (sync / push, verification after a push, linking): objects are
     * matched strictly by name, never by OID or attnum, which mean nothing in another database.
     */
    public SchemaDiffResult diffAcrossEnvironments(DatabaseSnapshot from, DatabaseSnapshot to) {
        return diffAcrossEnvironments(from, to, DiffMode.PLANNED);
    }

    public SchemaDiffResult diffAcrossEnvironments(DatabaseSnapshot from, DatabaseSnapshot to, DiffMode mode) {
        return run(from, to, true, false, null, mode);
    }

    /**
     * Cross-database compare: what must change in {@code currentDbState} (e.g. the database of a customer) to reach
     * {@code targetExternalSnapshot} (e.g. a snapshot exported from Dev).
     *
     * <p>With {@code matchByNameOnly} objects are paired strictly by their (case-sensitive) qualified names; OIDs and
     * attnums are ignored, so nothing is reported as renamed. Objects of the target missing in the current database are
     * {@code ADDED}; objects that exist only in the current database are {@code DELETED} with
     * {@link ChangeItem#isExtraInTargetDb()} set — dropping them is the caller's explicit decision.
     *
     * <p>Every change gets an {@link ChangeItem#getId() id}, and the result carries the UP / DOWN scripts statement by
     * statement ({@link SchemaDiffResult#getUpStatements()}), so a subset of the changes can be assembled into an
     * executable script without losing the dependency-safe order (see {@link #assemble}).
     */
    public SchemaDiffResult compare(DatabaseSnapshot currentDbState, DatabaseSnapshot targetExternalSnapshot,
                                    boolean matchByNameOnly) {
        return compare(currentDbState, targetExternalSnapshot, matchByNameOnly, null);
    }

    /** @param rules null: the default rules */
    public SchemaDiffResult compare(DatabaseSnapshot currentDbState, DatabaseSnapshot targetExternalSnapshot,
                                    boolean matchByNameOnly, DiffIgnoreRules rules) {
        return compare(currentDbState, targetExternalSnapshot, matchByNameOnly, rules, DiffMode.PLANNED);
    }

    /** @param mode {@link DiffMode#PLANNED} unless the changes already happened (discard: {@link DiffMode#APPLIED}) */
    public SchemaDiffResult compare(DatabaseSnapshot currentDbState, DatabaseSnapshot targetExternalSnapshot,
                                    boolean matchByNameOnly, DiffIgnoreRules rules, DiffMode mode) {
        return run(currentDbState, targetExternalSnapshot, matchByNameOnly, true, rules, mode);
    }

    /**
     * Joins the statements of the selected changes (in their original order). Statements that belong to no single
     * change (id -1) are kept whenever anything is selected.
     */
    public static String assemble(List<ScriptStatement> statements, Collection<Integer> selectedChangeIds) {
        Set<Integer> selected = new HashSet<>(selectedChangeIds);
        if (selected.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ScriptStatement s : statements) {
            if (s.changeId() < 0 || selected.contains(s.changeId())) {
                sb.append(s.sql()).append('\n');
            }
        }
        return sb.toString();
    }

    private SchemaDiffResult run(DatabaseSnapshot fromSnapshot, DatabaseSnapshot toSnapshot, boolean byNameOnly,
                                 boolean crossDatabase, DiffIgnoreRules requestedRules, DiffMode mode) {
        DiffTexts texts = DiffTexts.current();
        DiffIgnoreRules rules = requestedRules == null ? defaultRules() : requestedRules;
        boolean legacy = SnapshotFilter.legacy(fromSnapshot, toSnapshot);
        DatabaseSnapshot head = fromSnapshot == null ? DatabaseSnapshot.builder().build()
                : SnapshotFilter.apply(fromSnapshot, rules, legacy);
        DatabaseSnapshot live = toSnapshot == null ? DatabaseSnapshot.builder().build()
                : SnapshotFilter.apply(toSnapshot, rules, legacy);
        Run forward = new Run(head, live, true, byNameOnly, mode, texts);
        forward.execute();
        Run reverse = new Run(live, head, false, byNameOnly, mode.reverse(), texts);
        reverse.execute();

        Map<String, ChangeItem> inverseByKey = new HashMap<>();
        for (ChangeItem item : reverse.items) {
            inverseByKey.putIfAbsent(item.getPairKey() + "|" + item.getChangeType(), item);
        }
        Map<ChangeItem, Integer> forwardIdOfReverse = new java.util.IdentityHashMap<>();
        for (int i = 0; i < forward.items.size(); i++) {
            ChangeItem item = forward.items.get(i);
            item.setUpSql(forward.sqlOf(item));
            ChangeItem inverse = inverseByKey.get(item.getPairKey() + "|" + item.getChangeType().inverse());
            item.setDownSql(inverse == null ? "" : reverse.sqlOf(inverse));
            if (crossDatabase) {
                item.setId(i);
                item.setExtraInTargetDb(item.getChangeType() == ChangeType.DELETED);
                if (inverse != null) {
                    forwardIdOfReverse.put(inverse, i);
                }
            }
        }
        SchemaDiffResult.SchemaDiffResultBuilder result = SchemaDiffResult.builder()
                .hasChanges(!forward.statements.isEmpty())
                .mode(mode)
                .changes(forward.items)
                .upSql(forward.script())
                .downSql(reverse.script())
                .warnings(forward.warnings);
        if (crossDatabase) {
            Map<ChangeItem, Integer> forwardIds = new java.util.IdentityHashMap<>();
            for (int i = 0; i < forward.items.size(); i++) {
                forwardIds.put(forward.items.get(i), i);
            }
            result.upStatements(forward.ordered().stream()
                    .map(s -> new ScriptStatement(forwardIds.getOrDefault(s.item(), -1), s.sql())).toList());
            result.downStatements(reverse.ordered().stream()
                    .map(s -> new ScriptStatement(forwardIdOfReverse.getOrDefault(s.item(), -1), s.sql())).toList());
        }
        return result.build();
    }

    /** One direction of the comparison ("from" -> "to"). */
    private static final class Run {

        // execution order of the generated statements
        /** ALTER TYPE ... ADD VALUE cannot run in a transaction block on PostgreSQL 11: a step of its own. */
        private static final int ENUM_ADD_VALUE = 0;
        private static final int STEP_BREAK = 1;
        private static final int PRELUDE = 2;
        private static final int CREATE_SCHEMA = 3;
        private static final int CREATE_EXTENSION = 4;
        /** Rows of a table that is recreated are copied into a temporary table before anything is dropped. */
        private static final int BACKUP_DATA = 5;
        private static final int DROP_TRIGGER = 6;
        private static final int DROP_VIEW = 7;
        private static final int RELEASE_OWNERSHIP = 8;
        private static final int DROP_FOREIGN_KEY = 9;
        private static final int DROP_CONSTRAINT = 10;
        private static final int DROP_INDEX = 11;
        private static final int DETACH_PARTITION = 12;
        private static final int DROP_TABLE = 13;
        private static final int RENAME_RELATION = 14;
        private static final int RENAME_COLUMN = 15;
        private static final int RENAME_OTHER = 16;
        private static final int CREATE_TYPE = 17;
        private static final int DROP_SEQUENCE = 18;
        private static final int CREATE_SEQUENCE = 19;
        private static final int ALTER_SEQUENCE = 20;
        private static final int CREATE_FUNCTION = 21;
        private static final int CREATE_TABLE = 22;
        private static final int ALTER_TABLE = 23;
        /** Functions whose arguments / result use a table created by the same migration. */
        private static final int CREATE_FUNCTION_LATE = 24;
        private static final int ATTACH_PARTITION = 25;
        /** The copied rows go into the recreated table once it and its partitions exist, before keys and indexes. */
        private static final int RESTORE_DATA = 26;
        private static final int ADD_CONSTRAINT = 27;
        private static final int ADD_FOREIGN_KEY = 28;
        private static final int CREATE_INDEX = 29;
        private static final int SEQUENCE_OWNERSHIP = 30;
        private static final int CREATE_VIEW = 31;
        private static final int CREATE_VIEW_INDEX = 32;
        private static final int CREATE_TRIGGER = 33;
        private static final int COMMENT = 34;
        /** Owners, grants, tablespaces. */
        private static final int ACCESS = 35;
        /** Reference data: DELETE of removed rows, then INSERT ... ON CONFLICT of new and changed rows. */
        private static final int DATA_DELETE = 36;
        private static final int DATA_UPSERT = 37;
        private static final int DROP_FUNCTION = 38;
        private static final int DROP_TYPE = 39;
        private static final int DROP_EXTENSION = 40;
        private static final int DROP_SCHEMA = 41;

        /** Default privileges of a new object for other roles than its owner. */
        private static final List<String> NO_GRANTS = List.of();
        private static final List<String> FUNCTION_DEFAULT_GRANTS = List.of("PUBLIC=EXECUTE");

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
        /** warnings shown with the changes (worded for {@link #mode}) */
        final List<String> warnings = new ArrayList<>();
        /** warnings written into the script as SQL comments (always worded for running it) */
        final List<String> scriptWarnings = new ArrayList<>();
        private final DiffMode mode;
        private final DiffTexts tx;

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
        /** Tables whose partitioning changes are dropped and created again (with their partitions). */
        private final List<Recreation> recreations = new ArrayList<>();
        private final Map<String, Recreation> recreationByFromKey = new HashMap<>();
        private final Map<String, Recreation> recreationByToKey = new HashMap<>();
        /** from-keys of functions that are dropped and created again (their triggers are recreated too). */
        private final Set<String> recreatedFunctions = new HashSet<>();
        private boolean functionPrelude;
        private boolean enumStepBreak;

        Run(DatabaseSnapshot from, DatabaseSnapshot to, boolean liveIsTo, boolean byNameOnly, DiffMode mode,
            DiffTexts texts) {
            this.from = from;
            this.to = to;
            this.liveIsTo = liveIsTo;
            this.mode = mode;
            this.tx = texts;
            // identities (oid / attnum) mean something only inside one database of one environment
            this.sameDb = !byNameOnly && DatabaseSnapshot.sameDatabase(from.getSourceDatabaseId(), to.getSourceDatabaseId());
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

            planRecreations();

            diffSchemas();
            diffExtensions();
            diffEnums();
            diffFunctions();
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
                    .rootType(objectType).rootName(name).details(details).pairKey(pairKey).build();
            items.add(item);
            return item;
        }

        /** Marks a change of a column / constraint / index / trigger / comment as belonging to its object. */
        private static ChangeItem under(ChangeItem item, ObjectType rootType, String rootName) {
            item.setRootType(rootType);
            item.setRootName(rootName);
            return item;
        }

        /** A warning of the whole migration that belongs to one change. */
        private void warn(ChangeItem item, String message) {
            warnings.add(message);
            scriptWarnings.add(message);
            item.getWarnings().add(message);
        }

        /**
         * Data of a dropped table / partition / column: "already lost" when the drop already happened in the live
         * database, "will be lost" when the script runs; the SQL comment always says what running the script does.
         *
         * @param noun e.g. {@code noun.table}
         */
        private void warnDataLoss(ChangeItem item, String noun, String name, TableModel owner) {
            String size = lostSize(owner);
            String what = tx.t(noun, name);
            String sizeText = size.isEmpty() ? "" : " (" + size + ")";
            String shown = mode == DiffMode.PLANNED
                    ? tx.t("loss.warning.PLANNED", what, sizeText)
                    : DiffTexts.capitalize(tx.t("loss.warning." + mode, what, sizeText));
            warnings.add(shown);
            item.getWarnings().add(shown);
            scriptWarnings.add(tx.t("loss.warning.PLANNED", what, sizeText));
            item.setDataLoss(mode == DiffMode.APPLIED ? ChangeItem.ALREADY_LOST
                    : mode == DiffMode.PLANNED ? ChangeItem.WILL_BE_LOST : null);
            if (owner != null) {
                item.setLostRows(owner.getEstimatedRows() > 0 ? owner.getEstimatedRows() : null);
                item.setLostPrettySize(owner.getPrettySize());
            }
        }

        /** "147 GB / 1692.9M rows" of the table that held the data (empty when unknown). */
        private String lostSize(TableModel t) {
            if (t == null) {
                return "";
            }
            String rows = t.getEstimatedRows() > 0 ? rows(t.getEstimatedRows()) : null;
            String size = t.getPrettySize() == null || t.getPrettySize().isBlank() ? null : t.getPrettySize();
            if (size != null && rows != null) {
                return tx.t("size.both", size, rows);
            }
            if (rows != null) {
                return tx.t("size.rows", rows);
            }
            return size == null ? "" : size;
        }

        /** 1692.9M / 120k / 950 (as the UI shows row counts). */
        private static String rows(long n) {
            if (n >= 1_000_000) {
                return String.format(java.util.Locale.ROOT, "%.1fM", n / 1_000_000.0);
            }
            if (n >= 1_000) {
                return Math.round(n / 1_000.0) + "k";
            }
            return Long.toString(n);
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
            for (String w : scriptWarnings) {
                sb.append("-- WARNING: ").append(w.replace("\n", " ")).append('\n');
            }
            if (!scriptWarnings.isEmpty()) {
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
                    ChangeItem item = item(ChangeType.ADDED, ObjectType.SCHEMA, s, tx.t("schema.added"), pairKey("schema", null, s));
                    emit(CREATE_SCHEMA, item, "CREATE SCHEMA IF NOT EXISTS " + ident(s) + ";");
                }
            }
            for (String s : new TreeSet<>(fromSchemas)) {
                if (!toSchemas.contains(s) && !"public".equals(s)) {
                    ChangeItem item = item(ChangeType.DELETED, ObjectType.SCHEMA, s, tx.t("schema.dropped"), pairKey("schema", s, null));
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
                ChangeItem item = item(ChangeType.DELETED, tableType(f), key, tx.t("loss.details." + mode),
                        pairKey("table", id(f.getOid()), null));
                warnDataLoss(item, f.isPartition() ? "noun.partition" : "noun.table", key, f);
                emitDropForeignKeys(item, f);
                boolean droppedWithParent = f.isPartition() && droppedTables.contains(f.getParentTable());
                if (droppedWithParent) {
                    emit(DROP_TABLE, item, "-- " + tx.t("table.droppedWithParent", key, f.getParentTable()));
                } else {
                    emit(DROP_TABLE, item, "DROP TABLE " + qualified(f.getSchemaName(), f.getName()) + ";");
                }
            }

            for (Recreation r : recreations) {
                emitRecreation(r);
            }

            Map<String, TableModel> toByKey = to.getTables();
            List<TableModel> added = new ArrayList<>(tables.added);
            added.sort(Comparator.comparingInt((TableModel t) -> depth(t, toByKey)).thenComparing(TableModel::getQualifiedName));
            for (TableModel t : added) {
                String details = t.isPartition()
                        ? tx.t("partition.added", t.getParentTable(), t.getPartitionBoundExpr())
                        : t.isPartitioned() ? tx.t("table.addedPartitioned", t.getPartitionKeyDef()) : tx.t("table.added");
                ChangeItem item = item(ChangeType.ADDED, tableType(t), t.getQualifiedName(), details,
                        pairKey("table", null, id(t.getOid())));
                emitTableObjects(item, t);
                diffReferenceData(null, t);
            }

            for (Matching.Pair<TableModel> p : tables.matched) {
                diffTable(p.from(), p.to());
            }
        }

        /** Foreign keys of a table that is dropped: dropped first, so the order of the DROP TABLEs does not matter. */
        private void emitDropForeignKeys(ChangeItem item, TableModel f) {
            for (ConstraintModel c : sorted(f.getConstraints())) {
                if (c.getType() == ConstraintType.FOREIGN_KEY) {
                    emit(DROP_FOREIGN_KEY, item, "ALTER TABLE " + qualified(f.getSchemaName(), f.getName())
                            + " DROP CONSTRAINT " + ident(c.getName()) + ";");
                }
            }
        }

        /** CREATE TABLE with everything that belongs to the table: constraints, indexes, triggers, comments, grants. */
        private void emitTableObjects(ChangeItem item, TableModel t) {
            emit(CREATE_TABLE, item, createTable(t));
            for (ConstraintModel c : sorted(t.getConstraints())) {
                emit(c.getType() == ConstraintType.FOREIGN_KEY ? ADD_FOREIGN_KEY : ADD_CONSTRAINT, item, addConstraint(item, t, c));
            }
            for (IndexModel i : sorted(t.getIndexes())) {
                emit(CREATE_INDEX, item, createIndex(i));
            }
            String tq = qualified(t.getSchemaName(), t.getName());
            for (TriggerModel tr : sorted(t.getTriggers())) {
                emit(CREATE_TRIGGER, item, createTrigger(tr));
                String state = triggerState(tq, tr);
                if (state != null && !"O".equals(tr.getEnabled())) {
                    emit(CREATE_TRIGGER, item, state);
                }
            }
            if (t.getComment() != null) {
                emit(COMMENT, item, "COMMENT ON TABLE " + tq + " IS " + literal(t.getComment()) + ";");
            }
            for (ColumnModel c : byAttnumOrder(t)) {
                if (c.getComment() != null && !t.isPartition()) {
                    emit(COMMENT, item, "COMMENT ON COLUMN " + tq + "." + ident(c.getName()) + " IS "
                            + literal(c.getComment()) + ";");
                }
            }
            emitNewAccess(item, "ALTER TABLE " + tq, "TABLE " + tq, t.getOwner(), t.getAcl(), NO_GRANTS);
        }

        // ------------------------------------------------------------------ recreated tables (partitioning changes)

        /**
         * A table whose partitioning changes (not partitioned -> partitioned, partitioned -> not partitioned, another
         * partition key). PostgreSQL cannot alter that in place, so the table is dropped and created again: its rows
         * are copied into a temporary table first and inserted into the new table afterwards (rows are routed into the
         * new partitions). Its partitions, the foreign keys of other tables that reference it, the views that read it
         * and the sequences it owns are recreated with it - all statements belong to one change.
         */
        private static final class Recreation {
            final TableModel from;
            final TableModel to;
            /** "from" tables dropped together with the root (the root and its partitions). */
            final List<TableModel> dropped = new ArrayList<>();
            /** "to" tables created (the root and its partitions). */
            final List<TableModel> created = new ArrayList<>();
            ChangeItem item;

            Recreation(TableModel from, TableModel to) {
                this.from = from;
                this.to = to;
            }
        }

        private static boolean partitioningChanged(TableModel f, TableModel t) {
            return f.isPartitioned() != t.isPartitioned() || !Objects.equals(f.getPartitionKeyDef(), t.getPartitionKeyDef());
        }

        private void planRecreations() {
            Set<String> fromKeys = new HashSet<>();
            Set<String> toKeys = new HashSet<>();
            for (Matching.Pair<TableModel> p : tables.matched) {
                if (partitioningChanged(p.from(), p.to())) {
                    fromKeys.add(p.from().getQualifiedName());
                    toKeys.add(p.to().getQualifiedName());
                }
            }
            if (fromKeys.isEmpty()) {
                return;
            }
            // partitions of a recreated table are recreated with it (also partitions of partitions)
            List<Matching.Pair<TableModel>> members = new ArrayList<>();
            List<TableModel> removedMembers = new ArrayList<>();
            List<TableModel> addedMembers = new ArrayList<>();
            boolean grown = true;
            while (grown) {
                grown = false;
                for (Matching.Pair<TableModel> p : tables.matched) {
                    boolean member = fromKeys.contains(p.from().getQualifiedName())
                            || (p.from().isPartition() && fromKeys.contains(p.from().getParentTable()))
                            || (p.to().isPartition() && toKeys.contains(p.to().getParentTable()));
                    if (member && !members.contains(p)) {
                        members.add(p);
                        fromKeys.add(p.from().getQualifiedName());
                        toKeys.add(p.to().getQualifiedName());
                        grown = true;
                    }
                }
                for (TableModel f : tables.removed) {
                    if (f.isPartition() && fromKeys.contains(f.getParentTable()) && fromKeys.add(f.getQualifiedName())) {
                        removedMembers.add(f);
                        grown = true;
                    }
                }
                for (TableModel t : tables.added) {
                    if (t.isPartition() && toKeys.contains(t.getParentTable()) && toKeys.add(t.getQualifiedName())) {
                        addedMembers.add(t);
                        grown = true;
                    }
                }
            }
            tables.matched.removeAll(members);
            tables.removed.removeAll(removedMembers);
            tables.added.removeAll(addedMembers);

            // one recreation per topmost table, the partitions below it join it
            for (Matching.Pair<TableModel> p : members) {
                boolean root = !(p.from().isPartition() && fromKeys.contains(p.from().getParentTable()))
                        && !(p.to().isPartition() && toKeys.contains(p.to().getParentTable()));
                if (root) {
                    Recreation r = new Recreation(p.from(), p.to());
                    recreations.add(r);
                    recreationByFromKey.put(p.from().getQualifiedName(), r);
                    recreationByToKey.put(p.to().getQualifiedName(), r);
                }
            }
            Map<String, TableModel> fromTables = from.getTables();
            Map<String, TableModel> toTables = to.getTables();
            List<TableModel> fromMembers = new ArrayList<>(removedMembers);
            List<TableModel> toMembers = new ArrayList<>(addedMembers);
            for (Matching.Pair<TableModel> p : members) {
                fromMembers.add(p.from());
                toMembers.add(p.to());
            }
            for (TableModel f : fromMembers) {
                Recreation r = recreationByFromKey.get(topmost(f, fromKeys, fromTables));
                if (r != null) {
                    r.dropped.add(f);
                    recreationByFromKey.putIfAbsent(f.getQualifiedName(), r);
                }
            }
            for (TableModel t : toMembers) {
                Recreation r = recreationByToKey.get(topmost(t, toKeys, toTables));
                if (r != null) {
                    r.created.add(t);
                    recreationByToKey.putIfAbsent(t.getQualifiedName(), r);
                }
            }
            for (Recreation r : recreations) {
                r.created.sort(Comparator.comparingInt((TableModel t) -> depth(t, toTables)).thenComparing(TableModel::getQualifiedName));
            }
        }

        /** Key of the highest ancestor of {@code t} that is in {@code keys} (t itself when its parent is not). */
        private static String topmost(TableModel t, Set<String> keys, Map<String, TableModel> byKey) {
            TableModel cur = t;
            Set<String> seen = new HashSet<>();
            while (cur.isPartition() && keys.contains(cur.getParentTable()) && seen.add(cur.getQualifiedName())) {
                TableModel parent = byKey.get(cur.getParentTable());
                if (parent == null) {
                    break;
                }
                cur = parent;
            }
            return cur.getQualifiedName();
        }

        private void emitRecreation(Recreation r) {
            TableModel f = r.from;
            TableModel t = r.to;
            String fq = qualified(f.getSchemaName(), f.getName());
            String tq = qualified(t.getSchemaName(), t.getName());
            String change = describePartitioning(f) + " -> " + describePartitioning(t);
            ChangeItem item = item(ChangeType.MODIFIED, tableType(t), t.getQualifiedName(),
                    tx.t("recreate.details", change),
                    pairKey("table-recreate", id(f.getOid()), id(t.getOid())));
            r.item = item;
            warn(item, tx.t("recreate.warning", t.getQualifiedName(), change));

            String copy = "pg_temp." + ident("_pgvc_copy_" + recreations.indexOf(r));
            List<String> columns = copiedColumns(f, t);
            if (columns.isEmpty()) {
                warn(item, tx.t("recreate.noColumns", t.getQualifiedName()));
            } else {
                emit(BACKUP_DATA, item, "CREATE TEMPORARY TABLE " + ident("_pgvc_copy_" + recreations.indexOf(r))
                        + " AS SELECT * FROM " + fq + ";");
            }
            for (TableModel dropped : r.dropped) {
                emitDropForeignKeys(item, dropped);
            }
            emit(DROP_TABLE, item, "DROP TABLE " + fq + ";");
            for (TableModel created : r.created) {
                emitTableObjects(item, created);
            }
            if (!columns.isEmpty()) {
                Map<String, ColumnModel> fromColumns = f.getColumns();
                boolean overriding = false;
                List<String> select = new ArrayList<>();
                for (String name : columns) {
                    ColumnModel tc = t.getColumns().get(name);
                    ColumnModel fc = fromColumns.get(name);
                    overriding |= "ALWAYS".equalsIgnoreCase(tc.getIdentityGeneration());
                    select.add(fc.getDataType().equals(tc.getDataType()) ? ident(name)
                            : ident(name) + "::" + tc.getDataType());
                }
                emit(RESTORE_DATA, item, "INSERT INTO " + tq + " (" + columns.stream().map(SqlIdentifiers::ident)
                        .collect(Collectors.joining(", ")) + ")" + (overriding ? " OVERRIDING SYSTEM VALUE" : "")
                        + "\n    SELECT " + String.join(", ", select) + " FROM " + copy + ";");
                for (String name : columns) {
                    if (t.getColumns().get(name).getIdentityGeneration() != null) {
                        // the identity sequence of the new table starts at 1: continue after the copied values
                        emit(RESTORE_DATA, item, "SELECT pg_catalog.setval(pg_catalog.pg_get_serial_sequence("
                                + literal(tq) + ", " + literal(name) + "), m) FROM (SELECT max(" + ident(name)
                                + ") AS m FROM " + tq + ") s WHERE m IS NOT NULL;");
                    }
                }
                emit(RESTORE_DATA, item, "DROP TABLE " + copy + ";");
            }
            diffReferenceData(f, t);
        }

        /** Columns of the new table that are filled from the previous version (same name, not generated). */
        private static List<String> copiedColumns(TableModel f, TableModel t) {
            List<String> columns = new ArrayList<>();
            for (ColumnModel tc : byAttnumOrder(t)) {
                ColumnModel fc = f.getColumns().get(tc.getName());
                if (fc != null && tc.getGeneratedExpression() == null) {
                    columns.add(tc.getName());
                }
            }
            return columns;
        }

        /** The recreation that a foreign key of another table depends on (its referenced table is recreated). */
        private Recreation recreationReferencedBy(ConstraintModel fc, ConstraintModel tc) {
            if (fc != null && fc.getType() == ConstraintType.FOREIGN_KEY && fc.getReferencedTable() != null
                    && recreationByFromKey.containsKey(fc.getReferencedTable())) {
                return recreationByFromKey.get(fc.getReferencedTable());
            }
            if (tc != null && tc.getType() == ConstraintType.FOREIGN_KEY && tc.getReferencedTable() != null
                    && recreationByToKey.containsKey(tc.getReferencedTable())) {
                return recreationByToKey.get(tc.getReferencedTable());
            }
            return null;
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
            if (t.getTablespace() != null) {
                sb.append("\n    TABLESPACE ").append(ident(t.getTablespace()));
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

        private String addConstraint(ChangeItem item, TableModel t, ConstraintModel c) {
            String definition = c.getDefinition();
            List<String> missing = missingPartitionColumns(t, c);
            if (!missing.isEmpty()) {
                definition = withKeyColumns(definition, missing);
                warn(item, tx.t("pk.partitionColumns", c.getType(), c.getName(), t.getQualifiedName(), String.join(", ", missing)));
            }
            return "ALTER TABLE " + qualified(t.getSchemaName(), t.getName()) + " ADD CONSTRAINT " + ident(c.getName())
                    + " " + definition + ";";
        }

        /**
         * A PRIMARY KEY / UNIQUE constraint of a partitioned table must contain every column of the partition key.
         * Snapshots taken from PostgreSQL always do; this guards hand-edited or converted snapshots.
         */
        static List<String> missingPartitionColumns(TableModel t, ConstraintModel c) {
            if (!t.isPartitioned() || (c.getType() != ConstraintType.PRIMARY_KEY && c.getType() != ConstraintType.UNIQUE)
                    || c.getDefinition() == null || c.getColumns() == null || c.getColumns().isEmpty()) {
                return List.of();
            }
            List<String> missing = new ArrayList<>();
            for (String column : partitionKeyColumns(t.getPartitionKeyDef())) {
                if (!c.getColumns().contains(column) && !missing.contains(column)) {
                    missing.add(column);
                }
            }
            return missing;
        }

        /** Plain columns of a partition key such as {@code RANGE (created_at, "Region")}; expressions are skipped. */
        static List<String> partitionKeyColumns(String partitionKeyDef) {
            List<String> columns = new ArrayList<>();
            if (partitionKeyDef == null) {
                return columns;
            }
            int open = partitionKeyDef.indexOf('(');
            int close = partitionKeyDef.lastIndexOf(')');
            if (open < 0 || close <= open) {
                return columns;
            }
            for (String part : splitTopLevel(partitionKeyDef.substring(open + 1, close))) {
                String p = part.strip();
                java.util.regex.Matcher m = KEY_COLUMN.matcher(p);
                if (m.matches()) {
                    columns.add(m.group(1) != null ? m.group(1).replace("\"\"", "\"") : m.group(2));
                }
            }
            return columns;
        }

        private static final Pattern KEY_COLUMN = Pattern.compile(
                "(?:\"((?:[^\"]|\"\")+)\"|([a-z_][a-z0-9_$]*))(?:\\s+(?:COLLATE\\s+\\S+|[a-z_][a-z0-9_.]*_ops))*");

        private static List<String> splitTopLevel(String s) {
            List<String> parts = new ArrayList<>();
            int depth = 0;
            boolean quoted = false;
            int start = 0;
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                if (ch == '"') {
                    quoted = !quoted;
                } else if (!quoted && ch == '(') {
                    depth++;
                } else if (!quoted && ch == ')') {
                    depth--;
                } else if (!quoted && depth == 0 && ch == ',') {
                    parts.add(s.substring(start, i));
                    start = i + 1;
                }
            }
            parts.add(s.substring(start));
            return parts;
        }

        /** {@code PRIMARY KEY (id)} + [date] -> {@code PRIMARY KEY (id, date)} (the first column list of the definition). */
        static String withKeyColumns(String definition, List<String> columns) {
            int open = definition.indexOf('(');
            if (open < 0) {
                return definition;
            }
            int depth = 0;
            for (int i = open; i < definition.length(); i++) {
                char ch = definition.charAt(i);
                if (ch == '(') {
                    depth++;
                } else if (ch == ')' && --depth == 0) {
                    return definition.substring(0, i) + ", "
                            + columns.stream().map(SqlIdentifiers::ident).collect(Collectors.joining(", "))
                            + definition.substring(i);
                }
            }
            return definition;
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
                        tx.t("renamed", f.getQualifiedName()), pairKey("table-rename", fromId, tableId));
                if (!f.getSchemaName().equals(t.getSchemaName())) {
                    emit(RENAME_RELATION, item, "ALTER TABLE " + fq + " SET SCHEMA " + ident(t.getSchemaName()) + ";");
                }
                if (!f.getName().equals(t.getName())) {
                    emit(RENAME_RELATION, item, "ALTER TABLE " + qualified(t.getSchemaName(), f.getName())
                            + " RENAME TO " + ident(t.getName()) + ";");
                }
            }

            // a change of the partitioning is a recreation (planRecreations): matched tables keep their partitioning

            // partition membership / bounds
            String fromParentAsTo = f.isPartition() ? mapRelation(f.getParentTable()) : null;
            if (!Objects.equals(fromParentAsTo, t.getParentTable())
                    || !Objects.equals(f.getPartitionBoundExpr(), t.getPartitionBoundExpr())) {
                List<String> what = new ArrayList<>();
                if (f.isPartition()) {
                    what.add(tx.t("partition.detached", f.getParentTable()));
                }
                if (t.isPartition()) {
                    what.add(tx.t("partition.attached", t.getParentTable(), t.getPartitionBoundExpr()));
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
            diffIndexes(f.getIndexes(), t.getIndexes(), f.getSchemaName(), t.getSchemaName(), tableType(t),
                    t.getQualifiedName(), sameDb && f.getOid() == t.getOid(), CREATE_INDEX);
            diffTriggers(f, t, fq, tq);
            diffComment(tableType(t), t.getQualifiedName(), t.getQualifiedName(), tx.t("kind.table"), "TABLE " + tq,
                    f.getComment(), t.getComment(), "table:" + fromId, "table:" + tableId);
            diffAccess(tableType(t), t.getQualifiedName(), "ALTER TABLE " + tq, "TABLE " + tq, f.getOwner(),
                    t.getOwner(), f.getAcl(), t.getAcl(), f.getTablespace(), t.getTablespace(), "table:" + fromId,
                    "table:" + tableId);
            diffReferenceData(f, t);
        }

        private String describePartitioning(TableModel t) {
            return t.isPartitioned() ? tx.t("partitioning.by", t.getPartitionKeyDef()) : tx.t("partitioning.none");
        }

        private void diffColumns(TableModel f, TableModel t, String tq) {
            boolean byAttnum = sameDb && f.getOid() == t.getOid();
            Matching<ColumnModel> cm = Matching.of(byAttnumOrder(f), byAttnumOrder(t), c -> (long) c.getAttnum(),
                    ColumnModel::getName, byAttnum);
            String fTableId = id(f.getOid());
            String tTableId = id(t.getOid());

            for (ColumnModel c : cm.removed) {
                String name = t.getQualifiedName() + "." + c.getName();
                ChangeItem item = under(item(ChangeType.DELETED, ObjectType.COLUMN, name, tx.t("loss.details." + mode),
                        pairKey("column", fTableId + "/" + c.getAttnum(), null)), tableType(t), t.getQualifiedName());
                emit(ALTER_TABLE, item, "ALTER TABLE " + tq + " DROP COLUMN " + ident(c.getName()) + ";");
                affectedColumns.add(f.getQualifiedName() + "." + c.getName());
                affectedTables.add(f.getQualifiedName());
                warnDataLoss(item, "noun.column", name, f);
            }
            for (ColumnModel c : cm.added) {
                String name = t.getQualifiedName() + "." + c.getName();
                ChangeItem item = under(item(ChangeType.ADDED, ObjectType.COLUMN, name, tx.t("column.added", c.getDataType()),
                        pairKey("column", null, tTableId + "/" + c.getAttnum())), tableType(t), t.getQualifiedName());
                emit(ALTER_TABLE, item, "ALTER TABLE " + tq + " ADD COLUMN " + columnDefinition(c) + ";");
                if (c.getComment() != null) {
                    emit(COMMENT, item, "COMMENT ON COLUMN " + tq + "." + ident(c.getName()) + " IS "
                            + literal(c.getComment()) + ";");
                }
                if (!c.isNullable() && c.getDefaultValue() == null && c.getIdentityGeneration() == null
                        && c.getGeneratedExpression() == null) {
                    warn(item, tx.t("column.notNullWithoutDefault", name));
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
                ChangeItem item = under(item(ChangeType.RENAMED, ObjectType.COLUMN, name, tx.t("renamed", fc.getName()),
                        pairKey("column-rename", fromId, toId)), tableType(t), t.getQualifiedName());
                emit(RENAME_COLUMN, item, "ALTER TABLE " + tq + " RENAME COLUMN " + ident(fc.getName()) + " TO " + col + ";");
            }

            List<String> details = new ArrayList<>();
            List<String> sql = new ArrayList<>();
            List<String> columnWarnings = new ArrayList<>();
            boolean typeChanged = !fc.getDataType().equals(tc.getDataType());
            boolean collationChanged = !Objects.equals(fc.getCollation(), tc.getCollation());
            if (fc.getIdentityGeneration() != null && tc.getIdentityGeneration() == null) {
                sql.add(alter + "DROP IDENTITY;");
                details.add(tx.t("column.identityDropped"));
            }
            if (typeChanged || collationChanged) {
                String collate = collationChanged ? " COLLATE " + (tc.getCollation() == null ? "\"default\"" : tc.getCollation()) : "";
                sql.add(alter + "TYPE " + tc.getDataType() + collate
                        + (typeChanged ? " USING " + col + "::" + tc.getDataType() : "") + ";");
                if (typeChanged) {
                    details.add(tx.t("column.type", fc.getDataType(), tc.getDataType()));
                    columnWarnings.add(tx.t("column.typeWarning", name, fc.getDataType(), tc.getDataType()));
                }
                if (collationChanged) {
                    details.add(tx.t("column.collation", fc.getCollation(), tc.getCollation()));
                }
                affectedColumns.add(f.getQualifiedName() + "." + fc.getName());
                affectedTables.add(f.getQualifiedName());
            }
            if (!Objects.equals(fc.getDefaultValue(), tc.getDefaultValue())) {
                sql.add(alter + (tc.getDefaultValue() == null ? "DROP DEFAULT;" : "SET DEFAULT " + tc.getDefaultValue() + ";"));
                details.add(tc.getDefaultValue() == null ? tx.t("column.defaultDropped") : tx.t("column.default", tc.getDefaultValue()));
            }
            if (fc.isNullable() != tc.isNullable()) {
                if (!tc.isNullable()) {
                    sql.add(alter + "SET NOT NULL;");
                    details.add(tx.t("column.notNull"));
                } else if (tc.getIdentityGeneration() == null) {
                    sql.add(alter + "DROP NOT NULL;");
                    details.add(tx.t("column.nullable"));
                }
            }
            if (fc.getIdentityGeneration() == null && tc.getIdentityGeneration() != null) {
                sql.add(alter + "ADD GENERATED " + tc.getIdentityGeneration() + " AS IDENTITY;");
                details.add(tx.t("column.identity", tc.getIdentityGeneration()));
            } else if (fc.getIdentityGeneration() != null && tc.getIdentityGeneration() != null
                    && !fc.getIdentityGeneration().equals(tc.getIdentityGeneration())) {
                sql.add(alter + "SET GENERATED " + tc.getIdentityGeneration() + ";");
                details.add(tx.t("column.identity", tc.getIdentityGeneration()));
            }
            if (!Objects.equals(fc.getGeneratedExpression(), tc.getGeneratedExpression())) {
                String msg = tx.t("column.generatedWarning", name);
                columnWarnings.add(msg);
                details.add(tx.t("column.generatedChanged"));
                sql.add("-- MANUAL ACTION REQUIRED: " + msg);
            }
            if (!sql.isEmpty()) {
                ChangeItem item = under(item(ChangeType.MODIFIED, ObjectType.COLUMN, name, String.join(", ", details),
                        pairKey("column", fromId, toId)), tableType(t), t.getQualifiedName());
                sql.forEach(s -> emit(ALTER_TABLE, item, s));
                columnWarnings.forEach(w -> warn(item, w));
            }
            diffComment(tableType(t), t.getQualifiedName(), name, tx.t("kind.column"), "COLUMN " + tq + "." + col,
                    fc.getComment(), tc.getComment(), "column:" + fromId, "column:" + toId);
        }

        private void diffConstraints(TableModel f, TableModel t, String fq, String tq) {
            boolean byOid = sameDb && f.getOid() == t.getOid();
            Matching<ConstraintModel> cm = Matching.of(sorted(f.getConstraints()), sorted(t.getConstraints()),
                    ConstraintModel::getOid, ConstraintModel::getName, byOid);
            for (ConstraintModel c : cm.removed) {
                ChangeItem item = under(item(ChangeType.DELETED, ObjectType.CONSTRAINT, t.getQualifiedName() + "." + c.getName(),
                        tx.t("constraint.dropped", c.getType(), c.getDefinition()), pairKey("constraint", id(c.getOid()), null)),
                        tableType(t), t.getQualifiedName());
                emit(c.getType() == ConstraintType.FOREIGN_KEY ? DROP_FOREIGN_KEY : DROP_CONSTRAINT, item,
                        "ALTER TABLE " + fq + " DROP CONSTRAINT " + ident(c.getName()) + ";");
            }
            for (ConstraintModel c : cm.added) {
                ChangeItem item = under(item(ChangeType.ADDED, ObjectType.CONSTRAINT, t.getQualifiedName() + "." + c.getName(),
                        tx.t("constraint.added", c.getType(), c.getDefinition()), pairKey("constraint", null, id(c.getOid()))),
                        tableType(t), t.getQualifiedName());
                emit(c.getType() == ConstraintType.FOREIGN_KEY ? ADD_FOREIGN_KEY : ADD_CONSTRAINT, item, addConstraint(item, t, c));
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
                Recreation referenced = changed ? null : recreationReferencedBy(fc, tc);
                if (referenced != null) {
                    // the referenced table is dropped and created again: the key goes with it and comes back
                    emit(DROP_FOREIGN_KEY, referenced.item, "ALTER TABLE " + fq + " DROP CONSTRAINT " + ident(fc.getName()) + ";");
                    emit(ADD_FOREIGN_KEY, referenced.item, addConstraint(referenced.item, t, tc));
                    continue;
                }
                if (changed) {
                    ChangeItem item = under(item(ChangeType.MODIFIED, ObjectType.CONSTRAINT, name,
                            fc.getDefinition() + " -> " + tc.getDefinition(),
                            pairKey("constraint", id(fc.getOid()), id(tc.getOid()))), tableType(t), t.getQualifiedName());
                    emit(fc.getType() == ConstraintType.FOREIGN_KEY ? DROP_FOREIGN_KEY : DROP_CONSTRAINT, item,
                            "ALTER TABLE " + fq + " DROP CONSTRAINT " + ident(fc.getName()) + ";");
                    emit(tc.getType() == ConstraintType.FOREIGN_KEY ? ADD_FOREIGN_KEY : ADD_CONSTRAINT, item, addConstraint(item, t, tc));
                } else if (!fc.getName().equals(tc.getName())) {
                    ChangeItem item = under(item(ChangeType.RENAMED, ObjectType.CONSTRAINT, name, tx.t("renamed", fc.getName()),
                            pairKey("constraint-rename", id(fc.getOid()), id(tc.getOid()))), tableType(t),
                            t.getQualifiedName());
                    emit(RENAME_OTHER, item, "ALTER TABLE " + tq + " RENAME CONSTRAINT " + ident(fc.getName()) + " TO "
                            + ident(tc.getName()) + ";");
                }
            }
        }

        /** Indexes of one matched table or materialized view (index schema = schema of its table). */
        private void diffIndexes(Map<String, IndexModel> fromIndexes, Map<String, IndexModel> toIndexes,
                                 String fromSchema, String toSchema, ObjectType ownerType, String ownerKey,
                                 boolean byOid, int createPhase) {
            Matching<IndexModel> im = Matching.of(sorted(fromIndexes), sorted(toIndexes), IndexModel::getOid,
                    IndexModel::getName, byOid);
            for (IndexModel i : im.removed) {
                ChangeItem item = under(item(ChangeType.DELETED, ObjectType.INDEX, toSchema + "." + i.getName(),
                        tx.t("index.dropped", ownerKey), pairKey("index", id(i.getOid()), null)), ownerType, ownerKey);
                emit(DROP_INDEX, item, "DROP INDEX " + qualified(fromSchema, i.getName()) + ";");
            }
            for (IndexModel i : im.added) {
                ChangeItem item = under(item(ChangeType.ADDED, ObjectType.INDEX, toSchema + "." + i.getName(),
                        tx.t("index.added", ownerKey), pairKey("index", null, id(i.getOid()))), ownerType, ownerKey);
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
                    ChangeItem item = under(item(ChangeType.MODIFIED, ObjectType.INDEX, name, tx.t("index.changed"),
                            pairKey("index", id(fi.getOid()), id(ti.getOid()))), ownerType, ownerKey);
                    emit(DROP_INDEX, item, "DROP INDEX " + qualified(fromSchema, fi.getName()) + ";");
                    emit(createPhase, item, createIndex(ti));
                } else if (!fi.getName().equals(ti.getName())) {
                    ChangeItem item = under(item(ChangeType.RENAMED, ObjectType.INDEX, name, tx.t("renamed", fi.getName()),
                            pairKey("index-rename", id(fi.getOid()), id(ti.getOid()))), ownerType, ownerKey);
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
                        reason = tx.t("view.reason.dropped", rel);
                    } else if (recreationByFromKey.containsKey(rel)) {
                        reason = tx.t("view.reason.recreated", rel);
                    } else if (d.getColumn() != null && affectedColumns.contains(rel + "." + d.getColumn())) {
                        reason = tx.t("view.reason.column", rel + "." + d.getColumn());
                    } else if (d.getColumn() == null && affectedTables.contains(rel)) {
                        reason = tx.t("view.reason.columns", rel);
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
                            viewRecreateReason.putIfAbsent(v.getQualifiedName(), tx.t("view.reason.view", d.getRelation()));
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
                        tx.t("view.dropped", kind(v)), pairKey("view", id(v.getOid()), null)));
            }
            for (Matching.Pair<ViewModel> p : views.matched) {
                ViewModel f = p.from();
                ViewModel t = p.to();
                if (drop.contains(f.getQualifiedName())) {
                    String details = viewChanged(f, t) ? tx.t("view.changed", kind(t))
                            : tx.t("view.recreated", kind(t), viewRecreateReason.getOrDefault(f.getQualifiedName(),
                            tx.t("view.reason.dependency")));
                    ChangeItem item = item(ChangeType.MODIFIED, ObjectType.VIEW, t.getQualifiedName(), details,
                            pairKey("view", id(f.getOid()), id(t.getOid())));
                    itemByFromKey.put(f.getQualifiedName(), item);
                    itemByToKey.put(t.getQualifiedName(), item);
                    toCreate.put(t.getQualifiedName(), t);
                } else {
                    renameView(f, t);
                    if (t.isMaterialized()) {
                        diffIndexes(f.getIndexes(), t.getIndexes(), f.getSchemaName(), t.getSchemaName(),
                                ObjectType.VIEW, t.getQualifiedName(), sameDb && f.getOid() == t.getOid(),
                                CREATE_VIEW_INDEX);
                    }
                    String vq = qualified(t.getSchemaName(), t.getName());
                    String viewId = "view:" + id(f.getOid());
                    diffComment(ObjectType.VIEW, t.getQualifiedName(), t.getQualifiedName(), kind(t),
                            (t.isMaterialized() ? "MATERIALIZED VIEW " : "VIEW ") + vq, f.getComment(), t.getComment(),
                            viewId, "view:" + id(t.getOid()));
                    diffAccess(ObjectType.VIEW, t.getQualifiedName(),
                            (t.isMaterialized() ? "ALTER MATERIALIZED VIEW " : "ALTER VIEW ") + vq, "TABLE " + vq,
                            f.getOwner(), t.getOwner(), f.getAcl(), t.getAcl(), null, null, viewId,
                            "view:" + id(t.getOid()));
                }
            }
            for (ViewModel v : views.added) {
                ChangeItem item = item(ChangeType.ADDED, ObjectType.VIEW, v.getQualifiedName(), tx.t("view.added", kind(v)),
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

        private String kind(ViewModel v) {
            return v.isMaterialized() ? tx.t("kind.materializedView") : tx.t("kind.view");
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
            // a dropped and created view loses its comment, owner and grants: restore those of the target state
            String kw = v.isMaterialized() ? "MATERIALIZED VIEW " : "VIEW ";
            if (v.getComment() != null) {
                emit(COMMENT, item, "COMMENT ON " + kw + q + " IS " + literal(v.getComment()) + ";");
            }
            emitNewAccess(item, "ALTER " + kw + q, "TABLE " + q, v.getOwner(), v.getAcl(), NO_GRANTS);
        }

        private void renameView(ViewModel f, ViewModel t) {
            if (f.getSchemaName().equals(t.getSchemaName()) && f.getName().equals(t.getName())) {
                return;
            }
            String kw = t.isMaterialized() ? "ALTER MATERIALIZED VIEW " : "ALTER VIEW ";
            ChangeItem item = item(ChangeType.RENAMED, ObjectType.VIEW, t.getQualifiedName(),
                    tx.t("renamed", f.getQualifiedName()), pairKey("view-rename", id(f.getOid()), id(t.getOid())));
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
                ChangeItem item = item(ChangeType.DELETED, ObjectType.SEQUENCE, s.getQualifiedName(), tx.t("sequence.dropped"),
                        pairKey("sequence", id(s.getOid()), null));
                // IF EXISTS: a sequence owned by a dropped table / column is dropped together with it
                emit(DROP_SEQUENCE, item, "DROP SEQUENCE IF EXISTS " + qualified(s.getSchemaName(), s.getName()) + ";");
            }
            for (SequenceModel s : sm.added) {
                ChangeItem item = item(ChangeType.ADDED, ObjectType.SEQUENCE, s.getQualifiedName(), tx.t("sequence.added"),
                        pairKey("sequence", null, id(s.getOid())));
                emit(CREATE_SEQUENCE, item, "CREATE SEQUENCE " + qualified(s.getSchemaName(), s.getName())
                        + sequenceOptions(s) + ";");
                if (s.getOwnedByTable() != null) {
                    emit(SEQUENCE_OWNERSHIP, item, "ALTER SEQUENCE " + qualified(s.getSchemaName(), s.getName())
                            + " OWNED BY " + ownedBy(s) + ";");
                }
                String sq = qualified(s.getSchemaName(), s.getName());
                emitNewAccess(item, "ALTER SEQUENCE " + sq, "SEQUENCE " + sq, s.getOwner(), s.getAcl(), NO_GRANTS);
            }
            for (Matching.Pair<SequenceModel> p : sm.matched) {
                SequenceModel f = p.from();
                SequenceModel t = p.to();
                String fromId = id(f.getOid());
                String toId = id(t.getOid());
                String tq = qualified(t.getSchemaName(), t.getName());
                if (!f.getSchemaName().equals(t.getSchemaName()) || !f.getName().equals(t.getName())) {
                    ChangeItem item = item(ChangeType.RENAMED, ObjectType.SEQUENCE, t.getQualifiedName(),
                            tx.t("renamed", f.getQualifiedName()), pairKey("sequence-rename", fromId, toId));
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
                    details.add(tx.t("sequence.options"));
                    sql.add(new String[]{Integer.toString(ALTER_SEQUENCE), "ALTER SEQUENCE " + tq + sequenceOptions(t) + ";"});
                }
                String fromOwner = f.getOwnedByTable() == null ? null : mapRelation(f.getOwnedByTable()) + "." + f.getOwnedByColumn();
                String toOwner = t.getOwnedByTable() == null ? null : t.getOwnedByTable() + "." + t.getOwnedByColumn();
                if (!Objects.equals(fromOwner, toOwner)) {
                    details.add(toOwner == null ? tx.t("sequence.unowned") : tx.t("sequence.owned", toOwner));
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
                Recreation owner = Objects.equals(fromOwner, toOwner) && f.getOwnedByTable() != null
                        ? recreationByFromKey.get(f.getOwnedByTable()) : null;
                if (owner != null) {
                    // DROP TABLE would drop the sequence it owns: released before, owned by the new table after
                    emit(RELEASE_OWNERSHIP, owner.item, "ALTER SEQUENCE " + qualified(f.getSchemaName(), f.getName()) + " OWNED BY NONE;");
                    emit(SEQUENCE_OWNERSHIP, owner.item, "ALTER SEQUENCE " + tq + " OWNED BY " + ownedBy(t) + ";");
                }
                if (!sql.isEmpty()) {
                    ChangeItem item = item(ChangeType.MODIFIED, ObjectType.SEQUENCE, t.getQualifiedName(),
                            String.join(", ", details), pairKey("sequence", fromId, toId));
                    sql.forEach(s -> emit(Integer.parseInt(s[0]), item, s[1]));
                }
                diffAccess(ObjectType.SEQUENCE, t.getQualifiedName(), "ALTER SEQUENCE " + tq, "SEQUENCE " + tq,
                        f.getOwner(), t.getOwner(), f.getAcl(), t.getAcl(), null, null, "sequence:" + fromId,
                        "sequence:" + toId);
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

        // ------------------------------------------------------------------ extensions

        private static String extensionIdent(String name) {
            return '"' + name.replace("\"", "\"\"") + '"';
        }

        private void diffExtensions() {
            Map<String, ExtensionModel> fromExt = new TreeMap<>(from.getExtensions());
            Map<String, ExtensionModel> toExt = new TreeMap<>(to.getExtensions());
            for (ExtensionModel e : fromExt.values()) {
                if (toExt.containsKey(e.getName())) {
                    continue;
                }
                ChangeItem item = item(ChangeType.DELETED, ObjectType.EXTENSION, e.getName(),
                        tx.t("extension.dropped", e.getVersion()), pairKey("extension", e.getName(), null));
                warn(item, tx.t("extension.dropWarning", e.getName()));
                emit(DROP_EXTENSION, item, "DROP EXTENSION IF EXISTS " + extensionIdent(e.getName()) + ";");
            }
            for (ExtensionModel e : toExt.values()) {
                ExtensionModel f = fromExt.get(e.getName());
                if (f == null) {
                    ChangeItem item = item(ChangeType.ADDED, ObjectType.EXTENSION, e.getName(),
                            tx.t("extension.added", e.getVersion(), e.getSchemaName()),
                            pairKey("extension", null, e.getName()));
                    String schema = e.getSchemaName();
                    if (schema != null && !"public".equals(schema) && !from.getSchemas().contains(schema)
                            && !to.getSchemas().contains(schema)) {
                        emit(CREATE_EXTENSION, item, "CREATE SCHEMA IF NOT EXISTS " + ident(schema) + ";");
                    }
                    emit(CREATE_EXTENSION, item, "CREATE EXTENSION IF NOT EXISTS " + extensionIdent(e.getName())
                            + (schema == null ? "" : " WITH SCHEMA " + ident(schema)) + ";");
                    continue;
                }
                List<String> details = new ArrayList<>();
                List<String> sql = new ArrayList<>();
                if (!Objects.equals(f.getSchemaName(), e.getSchemaName()) && e.getSchemaName() != null) {
                    details.add(tx.t("extension.schema", f.getSchemaName(), e.getSchemaName()));
                    sql.add("ALTER EXTENSION " + extensionIdent(e.getName()) + " SET SCHEMA " + ident(e.getSchemaName()) + ";");
                }
                if (!Objects.equals(f.getVersion(), e.getVersion()) && e.getVersion() != null) {
                    details.add(tx.t("extension.version", f.getVersion(), e.getVersion()));
                    sql.add("ALTER EXTENSION " + extensionIdent(e.getName()) + " UPDATE TO " + literal(e.getVersion()) + ";");
                }
                if (!sql.isEmpty()) {
                    ChangeItem item = item(ChangeType.MODIFIED, ObjectType.EXTENSION, e.getName(),
                            String.join(", ", details), pairKey("extension", f.getName(), e.getName()));
                    sql.forEach(q -> emit(CREATE_EXTENSION, item, q));
                }
            }
        }

        // ------------------------------------------------------------------ enum types

        private void diffEnums() {
            Matching<EnumTypeModel> em = Matching.of(sorted(from.getEnumTypes()), sorted(to.getEnumTypes()),
                    EnumTypeModel::getOid, EnumTypeModel::getQualifiedName, sameDb);
            for (EnumTypeModel e : em.removed) {
                ChangeItem item = item(ChangeType.DELETED, ObjectType.TYPE, e.getQualifiedName(), tx.t("enum.dropped"),
                        pairKey("type", id(e.getOid()), null));
                emit(DROP_TYPE, item, "DROP TYPE " + qualified(e.getSchemaName(), e.getName()) + ";");
            }
            for (EnumTypeModel e : em.added) {
                ChangeItem item = item(ChangeType.ADDED, ObjectType.TYPE, e.getQualifiedName(),
                        tx.t("enum.added", String.join(", ", e.getValues())), pairKey("type", null, id(e.getOid())));
                String q = qualified(e.getSchemaName(), e.getName());
                emit(CREATE_TYPE, item, "CREATE TYPE " + q + " AS ENUM ("
                        + e.getValues().stream().map(v -> literal(v)).collect(Collectors.joining(", ")) + ");");
                if (e.getComment() != null) {
                    emit(COMMENT, item, "COMMENT ON TYPE " + q + " IS " + literal(e.getComment()) + ";");
                }
                if (e.getOwner() != null) {
                    emit(ACCESS, item, "ALTER TYPE " + q + " OWNER TO " + ident(e.getOwner()) + ";");
                }
            }
            for (Matching.Pair<EnumTypeModel> p : em.matched) {
                EnumTypeModel f = p.from();
                EnumTypeModel t = p.to();
                String fromId = id(f.getOid());
                String toId = id(t.getOid());
                String fq = qualified(f.getSchemaName(), f.getName());
                String tq = qualified(t.getSchemaName(), t.getName());
                if (!f.getSchemaName().equals(t.getSchemaName()) || !f.getName().equals(t.getName())) {
                    ChangeItem item = item(ChangeType.RENAMED, ObjectType.TYPE, t.getQualifiedName(),
                            tx.t("renamed", f.getQualifiedName()), pairKey("type-rename", fromId, toId));
                    if (!f.getSchemaName().equals(t.getSchemaName())) {
                        emit(RENAME_OTHER, item, "ALTER TYPE " + fq + " SET SCHEMA " + ident(t.getSchemaName()) + ";");
                    }
                    if (!f.getName().equals(t.getName())) {
                        emit(RENAME_OTHER, item, "ALTER TYPE " + qualified(t.getSchemaName(), f.getName())
                                + " RENAME TO " + ident(t.getName()) + ";");
                    }
                }
                List<String> fv = f.getValues();
                List<String> tv = t.getValues();
                if (!fv.equals(tv)) {
                    if (isSubsequence(fv, tv)) {
                        List<String> added = tv.stream().filter(v -> !fv.contains(v)).toList();
                        ChangeItem item = item(ChangeType.MODIFIED, ObjectType.TYPE, t.getQualifiedName(),
                                tx.t("enum.valuesAdded", String.join(", ", added)), pairKey("type", fromId, toId));
                        // before the rename (phase RENAME_OTHER) the type still has its old name
                        for (int i = 0; i < tv.size(); i++) {
                            String v = tv.get(i);
                            if (fv.contains(v)) {
                                continue;
                            }
                            // new labels are added in order, so the previous one always exists already
                            String position = i > 0 ? " AFTER " + literal(tv.get(i - 1))
                                    : fv.isEmpty() ? "" : " BEFORE " + literal(fv.get(0));
                            emit(ENUM_ADD_VALUE, item, "ALTER TYPE " + fq + " ADD VALUE IF NOT EXISTS " + literal(v)
                                    + position + ";");
                        }
                        if (!enumStepBreak) {
                            enumStepBreak = true;
                            emit(STEP_BREAK, null, com.vodianoy.pgvc.execution.SqlScripts.STEP_MARKER);
                        }
                    } else {
                        String msg = tx.t("enum.reordered", t.getQualifiedName(), String.join(", ", fv), String.join(", ", tv));
                        ChangeItem item = item(ChangeType.MODIFIED, ObjectType.TYPE, t.getQualifiedName(), msg,
                                pairKey("type", fromId, toId));
                        warn(item, msg);
                        emit(CREATE_TYPE, item, "-- MANUAL ACTION REQUIRED: " + msg);
                    }
                }
                diffComment(ObjectType.TYPE, t.getQualifiedName(), t.getQualifiedName(), tx.t("kind.type"), "TYPE " + tq,
                        f.getComment(), t.getComment(), "type:" + fromId, "type:" + toId);
                diffAccess(ObjectType.TYPE, t.getQualifiedName(), "ALTER TYPE " + tq, null, f.getOwner(),
                        t.getOwner(), null, null, null, null, "type:" + fromId, "type:" + toId);
            }
        }

        /** {@code a} appears in {@code b} in the same order (only additions). */
        private static boolean isSubsequence(List<String> a, List<String> b) {
            int i = 0;
            for (String v : b) {
                if (i < a.size() && a.get(i).equals(v)) {
                    i++;
                }
            }
            return i == a.size() && b.containsAll(a);
        }

        // ------------------------------------------------------------------ functions

        private static ObjectType functionType(FunctionModel f) {
            return f.isProcedure() ? ObjectType.PROCEDURE : ObjectType.FUNCTION;
        }

        private static String signature(FunctionModel f) {
            return qualified(f.getSchemaName(), f.getName()) + "(" + nullToEmpty(f.getIdentityArguments()) + ")";
        }

        private static String routineKeyword(FunctionModel f) {
            return f.isProcedure() ? "PROCEDURE" : "FUNCTION";
        }

        private static String nullToEmpty(String s) {
            return s == null ? "" : s;
        }

        private void diffFunctions() {
            Matching<FunctionModel> fm = Matching.of(sorted(from.getFunctions()), sorted(to.getFunctions()),
                    FunctionModel::getOid, FunctionModel::getKey, sameDb);
            for (FunctionModel f : fm.removed) {
                ChangeItem item = item(ChangeType.DELETED, functionType(f), f.getKey(),
                        tx.t("function.dropped", routineKind(f)), pairKey("function", id(f.getOid()), null));
                emit(DROP_FUNCTION, item, "DROP " + routineKeyword(f) + " " + signature(f) + ";");
            }
            for (FunctionModel t : fm.added) {
                ChangeItem item = item(ChangeType.ADDED, functionType(t), t.getKey(),
                        tx.t("function.added", routineKind(t), describeResult(t)), pairKey("function", null, id(t.getOid())));
                emitCreateFunction(item, t);
            }
            for (Matching.Pair<FunctionModel> p : fm.matched) {
                FunctionModel f = p.from();
                FunctionModel t = p.to();
                String fromId = id(f.getOid());
                String toId = id(t.getOid());
                if (!f.getSchemaName().equals(t.getSchemaName()) || !f.getName().equals(t.getName())) {
                    ChangeItem item = item(ChangeType.RENAMED, functionType(t), t.getKey(), tx.t("renamed", f.getKey()),
                            pairKey("function-rename", fromId, toId));
                    if (!f.getSchemaName().equals(t.getSchemaName())) {
                        emit(RENAME_OTHER, item, "ALTER " + routineKeyword(f) + " " + signature(f) + " SET SCHEMA "
                                + ident(t.getSchemaName()) + ";");
                    }
                    if (!f.getName().equals(t.getName())) {
                        FunctionModel moved = f.toBuilder().schemaName(t.getSchemaName()).build();
                        emit(RENAME_OTHER, item, "ALTER " + routineKeyword(f) + " " + signature(moved) + " RENAME TO "
                                + ident(t.getName()) + ";");
                    }
                }
                boolean recreated = false;
                if (!normalizeFunction(f).equals(normalizeFunction(t))) {
                    boolean signatureChanged = !Objects.equals(f.getKind(), t.getKind())
                            || !Objects.equals(f.getResultType(), t.getResultType());
                    if (signatureChanged) {
                        recreated = true;
                        recreatedFunctions.add(f.getKey());
                        String details = f.isProcedure() != t.isProcedure()
                                ? tx.t("function.kindChanged", routineKind(f), routineKind(t))
                                : tx.t("function.returnType", f.getResultType(), t.getResultType());
                        ChangeItem item = item(ChangeType.MODIFIED, functionType(t), t.getKey(),
                                tx.t("function.recreated", details), pairKey("function", fromId, toId));
                        warn(item, tx.t("function.recreateWarning", DiffTexts.capitalize(routineKind(t)), t.getKey(), details));
                        FunctionModel renamed = f.toBuilder().schemaName(t.getSchemaName()).name(t.getName()).build();
                        emit(CREATE_FUNCTION, item, "DROP " + routineKeyword(f) + " " + signature(renamed) + ";");
                        emitCreateFunction(item, t);
                    } else {
                        ChangeItem item = item(ChangeType.MODIFIED, functionType(t), t.getKey(),
                                tx.t("function.replaced"), pairKey("function", fromId, toId));
                        emitPrelude();
                        emit(functionPhase(t), item, createFunction(t));
                    }
                }
                if (!recreated) {
                    String target = routineKeyword(t) + " " + signature(t);
                    diffComment(functionType(t), t.getKey(), t.getKey(), routineKind(t), target,
                            f.getComment(), t.getComment(), "function:" + fromId, "function:" + toId);
                    diffAccess(functionType(t), t.getKey(), "ALTER " + target, target, f.getOwner(), t.getOwner(),
                            f.getAcl(), t.getAcl(), null, null, "function:" + fromId, "function:" + toId);
                }
            }
        }

        private String describeResult(FunctionModel f) {
            return f.getResultType() == null ? "" : tx.t("function.returning", f.getResultType());
        }

        /** "function" / "procedure" in the language of the texts. */
        private String routineKind(FunctionModel f) {
            return f.isProcedure() ? tx.t("kind.procedure") : tx.t("kind.function");
        }

        /** CREATE OR REPLACE with the comment, owner and grants of the target state. */
        private void emitCreateFunction(ChangeItem item, FunctionModel t) {
            emitPrelude();
            emit(functionPhase(t), item, createFunction(t));
            String target = routineKeyword(t) + " " + signature(t);
            if (t.getComment() != null) {
                emit(COMMENT, item, "COMMENT ON " + target + " IS " + literal(t.getComment()) + ";");
            }
            emitNewAccess(item, "ALTER " + target, target, t.getOwner(), t.getAcl(), FUNCTION_DEFAULT_GRANTS);
        }

        /** Function bodies are checked when they run, not when they are created (they may use later objects). */
        private void emitPrelude() {
            if (!functionPrelude) {
                functionPrelude = true;
                emit(PRELUDE, null, "SET LOCAL check_function_bodies = off;");
            }
        }

        private static String createFunction(FunctionModel f) {
            String def = f.getDefinition() == null ? "" : f.getDefinition().strip();
            return def.endsWith(";") ? def : def + ";";
        }

        /** After the new tables when the signature uses the row type of a table created by this migration. */
        private int functionPhase(FunctionModel f) {
            String signature = nullToEmpty(f.getIdentityArguments()) + " " + nullToEmpty(f.getResultType());
            for (TableModel t : tables.added) {
                Pattern p = Pattern.compile("(^|[^\\w.\"])(" + Pattern.quote(t.getSchemaName()) + "\\.)?"
                        + Pattern.quote(ident(t.getName())) + "($|[^\\w\"])");
                if (p.matcher(signature).find()) {
                    return CREATE_FUNCTION_LATE;
                }
            }
            return CREATE_FUNCTION;
        }

        /** Definition without the routine's own name (renames are reported separately) and outer whitespace. */
        private static String normalizeFunction(FunctionModel f) {
            String def = f.getDefinition() == null ? "" : f.getDefinition().strip();
            if (def.endsWith(";")) {
                def = def.substring(0, def.length() - 1).strip();
            }
            String header = qualified(f.getSchemaName(), f.getName()) + "(";
            int idx = def.indexOf(header);
            if (idx >= 0) {
                def = def.substring(0, idx) + "?(" + def.substring(idx + header.length());
            }
            return def.replace("\r\n", "\n");
        }

        // ------------------------------------------------------------------ triggers

        private void diffTriggers(TableModel f, TableModel t, String fq, String tq) {
            boolean byOid = sameDb && f.getOid() == t.getOid();
            Matching<TriggerModel> tm = Matching.of(sorted(f.getTriggers()), sorted(t.getTriggers()),
                    TriggerModel::getOid, TriggerModel::getName, byOid);
            ObjectType rootType = tableType(t);
            String root = t.getQualifiedName();
            for (TriggerModel tr : tm.removed) {
                ChangeItem item = under(item(ChangeType.DELETED, ObjectType.TRIGGER, root + "." + tr.getName(),
                        tx.t("trigger.dropped"), pairKey("trigger", id(tr.getOid()), null)), rootType, root);
                emit(DROP_TRIGGER, item, "DROP TRIGGER IF EXISTS " + ident(tr.getName()) + " ON " + fq + ";");
            }
            for (TriggerModel tr : tm.added) {
                ChangeItem item = under(item(ChangeType.ADDED, ObjectType.TRIGGER, root + "." + tr.getName(),
                        tx.t("trigger.added", tr.getFunctionKey()), pairKey("trigger", null, id(tr.getOid()))),
                        rootType, root);
                emit(CREATE_TRIGGER, item, createTrigger(tr));
                if (!"O".equals(tr.getEnabled()) && tr.getEnabled() != null) {
                    emit(CREATE_TRIGGER, item, triggerState(tq, tr));
                }
            }
            for (Matching.Pair<TriggerModel> p : tm.matched) {
                TriggerModel fr = p.from();
                TriggerModel tr = p.to();
                String name = root + "." + tr.getName();
                boolean definitionChanged = !normalizeTrigger(fr, f).equals(normalizeTrigger(tr, t));
                boolean functionRecreated = recreatedFunctions.contains(fr.getFunctionKey());
                if (definitionChanged || functionRecreated) {
                    ChangeItem item = under(item(ChangeType.MODIFIED, ObjectType.TRIGGER, name,
                            definitionChanged ? tx.t("trigger.changed")
                                    : tx.t("trigger.functionRecreated", fr.getFunctionKey()),
                            pairKey("trigger", id(fr.getOid()), id(tr.getOid()))), rootType, root);
                    emit(DROP_TRIGGER, item, "DROP TRIGGER IF EXISTS " + ident(fr.getName()) + " ON " + fq + ";");
                    emit(CREATE_TRIGGER, item, createTrigger(tr));
                    if (!"O".equals(tr.getEnabled()) && tr.getEnabled() != null) {
                        emit(CREATE_TRIGGER, item, triggerState(tq, tr));
                    }
                    continue;
                }
                if (!fr.getName().equals(tr.getName())) {
                    ChangeItem item = under(item(ChangeType.RENAMED, ObjectType.TRIGGER, name,
                            tx.t("renamed", fr.getName()), pairKey("trigger-rename", id(fr.getOid()), id(tr.getOid()))),
                            rootType, root);
                    emit(RENAME_OTHER, item, "ALTER TRIGGER " + ident(fr.getName()) + " ON " + tq + " RENAME TO "
                            + ident(tr.getName()) + ";");
                }
                if (!Objects.equals(fr.getEnabled(), tr.getEnabled()) && tr.getEnabled() != null) {
                    ChangeItem item = under(item(ChangeType.MODIFIED, ObjectType.TRIGGER, name,
                            describeTriggerState(tr.getEnabled()), pairKey("trigger", id(fr.getOid()), id(tr.getOid()))),
                            rootType, root);
                    emit(CREATE_TRIGGER, item, triggerState(tq, tr));
                }
            }
        }

        /** pg_get_triggerdef as an executable statement (EXECUTE FUNCTION works on PostgreSQL 11+). */
        private static String createTrigger(TriggerModel tr) {
            String def = tr.getDefinition() == null ? "" : tr.getDefinition().strip();
            if (def.endsWith(";")) {
                def = def.substring(0, def.length() - 1).strip();
            }
            return def.replace(" EXECUTE PROCEDURE ", " EXECUTE FUNCTION ") + ";";
        }

        private static String triggerState(String tq, TriggerModel tr) {
            String action = switch (tr.getEnabled() == null ? "O" : tr.getEnabled()) {
                case "D" -> "DISABLE TRIGGER ";
                case "R" -> "ENABLE REPLICA TRIGGER ";
                case "A" -> "ENABLE ALWAYS TRIGGER ";
                default -> "ENABLE TRIGGER ";
            };
            return "ALTER TABLE " + tq + " " + action + ident(tr.getName()) + ";";
        }

        private String describeTriggerState(String enabled) {
            return switch (enabled) {
                case "D" -> tx.t("trigger.disabled");
                case "R" -> tx.t("trigger.replica");
                case "A" -> tx.t("trigger.always");
                default -> tx.t("trigger.enabled");
            };
        }

        /**
         * Definition without the trigger and table names (renames are separate changes) and with EXECUTE FUNCTION
         * (PostgreSQL 11 prints EXECUTE PROCEDURE).
         */
        private static String normalizeTrigger(TriggerModel tr, TableModel table) {
            String def = createTrigger(tr);
            String qualifiedName = qualified(table.getSchemaName(), table.getName());
            def = def.replace(" ON " + qualifiedName + " ", " ON ? ")
                    .replace(" ON " + ident(table.getName()) + " ", " ON ? ");
            String name = ident(tr.getName());
            for (String prefix : List.of("CREATE TRIGGER ", "CREATE CONSTRAINT TRIGGER ")) {
                if (def.startsWith(prefix + name + " ")) {
                    def = prefix + "? " + def.substring(prefix.length() + name.length() + 1);
                    break;
                }
            }
            return def.replaceAll("\\s+", " ");
        }

        // ------------------------------------------------------------------ comments

        /**
         * COMMENT ON for a matched object whose comment differs.
         *
         * @param kind   "table", "column", "view" ... (for the description)
         * @param target object part of the statement, e.g. {@code TABLE sales.orders}
         */
        private void diffComment(ObjectType rootType, String rootName, String objectName, String kind, String target,
                                 String fromComment, String toComment, String fromId, String toId) {
            if (Objects.equals(fromComment, toComment)) {
                return;
            }
            String details = toComment == null ? tx.t("comment.removed", kind)
                    : tx.t("comment.set", kind, abbreviate(toComment));
            // always MODIFIED: a comment is a property of its object (never a "destructive" change)
            ChangeItem item = under(item(ChangeType.MODIFIED, ObjectType.COMMENT, objectName, details,
                    pairKey("comment", fromComment == null ? null : fromId, toComment == null ? null : toId)),
                    rootType, rootName);
            emit(COMMENT, item, "COMMENT ON " + target + " IS " + literal(toComment) + ";");
        }

        private static String abbreviate(String s) {
            String one = s.replaceAll("\\s+", " ").strip();
            return one.length() <= 80 ? "'" + one + "'" : "'" + one.substring(0, 77) + "...'";
        }

        // ------------------------------------------------------------------ owners, grants, tablespaces

        /**
         * Owner, grants and tablespace of a matched object. A null value on either side means "not tracked" (ignore
         * rules or an older snapshot) and is not compared.
         *
         * @param alterPrefix e.g. {@code ALTER TABLE sales.orders}
         * @param grantTarget e.g. {@code TABLE sales.orders}; null: the object has no tracked grants
         */
        private void diffAccess(ObjectType type, String name, String alterPrefix, String grantTarget, String fromOwner,
                                String toOwner, List<String> fromAcl, List<String> toAcl, String fromTablespace,
                                String toTablespace, String fromId, String toId) {
            List<String> details = new ArrayList<>();
            List<String> sql = new ArrayList<>();
            if (fromOwner != null && toOwner != null && !fromOwner.equals(toOwner)) {
                details.add(tx.t("access.owner", fromOwner, toOwner));
                sql.add(alterPrefix + " OWNER TO " + ident(toOwner) + ";");
            }
            if (grantTarget != null && fromAcl != null && toAcl != null) {
                List<String> grants = grantStatements(grantTarget, fromAcl, toAcl);
                if (!grants.isEmpty()) {
                    details.add(tx.t("access.grants", describeAcl(fromAcl), describeAcl(toAcl)));
                    sql.addAll(grants);
                }
            }
            boolean tablespaceChanged = fromTablespace != null && toTablespace != null && !fromTablespace.equals(toTablespace);
            if (tablespaceChanged) {
                details.add(tx.t("access.tablespace", fromTablespace, toTablespace));
                sql.add(alterPrefix + " SET TABLESPACE " + ident(toTablespace) + ";");
            }
            if (sql.isEmpty()) {
                return;
            }
            ChangeItem item = item(ChangeType.MODIFIED, type, name, String.join(", ", details),
                    pairKey("access", fromId, toId));
            if (tablespaceChanged) {
                warn(item, tx.t("access.tablespaceWarning", name));
            }
            sql.forEach(q -> emit(ACCESS, item, q));
        }

        /** Owner and grants of a newly created object (compared with the default privileges of a new object). */
        private void emitNewAccess(ChangeItem item, String alterPrefix, String grantTarget, String owner,
                                   List<String> acl, List<String> defaultAcl) {
            if (owner != null) {
                emit(ACCESS, item, alterPrefix + " OWNER TO " + ident(owner) + ";");
            }
            if (acl != null) {
                grantStatements(grantTarget, defaultAcl, acl).forEach(q -> emit(ACCESS, item, q));
            }
        }

        private String describeAcl(List<String> acl) {
            return acl.isEmpty() ? tx.t("access.noGrants") : String.join(" ", acl);
        }

        /** GRANT / REVOKE turning {@code fromAcl} into {@code toAcl} (entries {@code grantee=PRIVILEGE[*]}). */
        static List<String> grantStatements(String target, List<String> fromAcl, List<String> toAcl) {
            Set<String> f = new LinkedHashSet<>(fromAcl);
            Set<String> t = new LinkedHashSet<>(toAcl);
            List<String> sql = new ArrayList<>();
            for (String e : f) {
                if (t.contains(e)) {
                    continue;
                }
                AclEntry a = AclEntry.parse(e);
                if (a.grantable() && t.contains(a.grantee() + "=" + a.privilege())) {
                    sql.add("REVOKE GRANT OPTION FOR " + a.privilege() + " ON " + target + " FROM " + a.grantee() + ";");
                } else if (!a.grantable() && t.contains(e + "*")) {
                    continue; // upgraded to WITH GRANT OPTION below
                } else {
                    sql.add("REVOKE " + a.privilege() + " ON " + target + " FROM " + a.grantee() + ";");
                }
            }
            for (String e : t) {
                if (f.contains(e)) {
                    continue;
                }
                AclEntry a = AclEntry.parse(e);
                if (!a.grantable() && f.contains(e + "*")) {
                    continue; // handled by REVOKE GRANT OPTION FOR above
                }
                sql.add("GRANT " + a.privilege() + " ON " + target + " TO " + a.grantee()
                        + (a.grantable() ? " WITH GRANT OPTION" : "") + ";");
            }
            return sql;
        }

        // ------------------------------------------------------------------ reference data

        /**
         * Rows of a reference-data table, matched by PRIMARY KEY. Compared when the "to" state has the rows. When the
         * "from" state has no rows of the table (a new table, or a table that was not tracked yet) every row is an
         * INSERT ... ON CONFLICT and nothing is deleted; when the "to" state has no rows (not tracked there) nothing
         * is generated — a table that is simply not tracked on one side never turns into DELETEs.
         *
         * @param f the table in the "from" state, null when it is created by this migration
         */
        private void diffReferenceData(TableModel f, TableModel t) {
            ReferenceDataModel to = t.getReferenceData();
            if (to == null) {
                return;
            }
            boolean newlyTracked = f == null || f.getReferenceData() == null;
            ReferenceDataModel fromData = newlyTracked ? emptyLike(to) : f.getReferenceData();
            String name = t.getQualifiedName();
            String pairFrom = f == null ? null : "data:" + id(f.getOid());
            String pairTo = "data:" + id(t.getOid());
            if (to.isTruncated() || fromData.isTruncated()) {
                // over the row limit: not compared (the reference-data list shows it), never a permanent change
                return;
            }
            if (!newlyTracked && !fromData.getPrimaryKey().equals(to.getPrimaryKey())) {
                if (!fromData.getRows().isEmpty() || !to.getRows().isEmpty()) {
                    ChangeItem item = under(item(ChangeType.MODIFIED, ObjectType.DATA, name,
                            tx.t("data.pkChanged"), pairKey("data-skipped", pairFrom, pairTo)),
                            tableType(t), name);
                    warn(item, tx.t("data.pkWarning", name, String.join(", ", fromData.getPrimaryKey()),
                            String.join(", ", to.getPrimaryKey())));
                    emit(DATA_UPSERT, item, "-- MANUAL ACTION REQUIRED: " + tx.t("data.pkManual", name));
                }
                return;
            }
            if (!newlyTracked && Objects.equals(fromData.getRowsHash(), to.getRowsHash())
                    && fromData.getColumns().equals(to.getColumns())) {
                return;
            }

            Map<List<String>, List<String>> fromRows = byKey(fromData);
            Map<List<String>, List<String>> toRows = byKey(to);
            List<String> shared = to.getColumns().stream().filter(c -> fromData.indexOf(c) >= 0).toList();
            String tq = qualified(t.getSchemaName(), t.getName());

            List<String> deletes = new ArrayList<>();
            for (Map.Entry<List<String>, List<String>> e : fromRows.entrySet()) {
                if (!toRows.containsKey(e.getKey())) {
                    deletes.add(deleteRow(tq, fromData, e.getValue()));
                }
            }
            List<String> upserts = new ArrayList<>();
            int added = 0;
            int changed = 0;
            for (Map.Entry<List<String>, List<String>> e : toRows.entrySet()) {
                List<String> old = fromRows.get(e.getKey());
                if (old == null) {
                    added++;
                    upserts.add(upsertRow(tq, to, e.getValue(), t));
                } else if (differs(fromData, old, to, e.getValue(), shared)) {
                    changed++;
                    upserts.add(upsertRow(tq, to, e.getValue(), t));
                }
            }
            if (deletes.isEmpty() && upserts.isEmpty()) {
                return;
            }
            List<String> parts = new ArrayList<>();
            if (added > 0) {
                parts.add(tx.t("data.added", added));
            }
            if (changed > 0) {
                parts.add(tx.t("data.changed", changed));
            }
            if (!deletes.isEmpty()) {
                parts.add(tx.t("data.deleted", deletes.size()));
            }
            ChangeItem item = under(item(f == null ? ChangeType.ADDED : ChangeType.MODIFIED, ObjectType.DATA, name,
                    tx.t(f != null && newlyTracked ? "data.summaryNew" : "data.summary", String.join(", ", parts)),
                    pairKey("data", pairFrom, pairTo)), tableType(t), name);
            deletes.forEach(q -> emit(DATA_DELETE, item, q));
            upserts.forEach(q -> emit(DATA_UPSERT, item, q));
        }

        private static ReferenceDataModel emptyLike(ReferenceDataModel m) {
            return ReferenceDataModel.builder().columns(m.getColumns()).columnTypes(m.getColumnTypes())
                    .primaryKey(m.getPrimaryKey()).rows(new ArrayList<>()).rowCount(0)
                    .rowsHash(ReferenceDataModel.hash(List.of())).build();
        }

        /** Rows by their primary-key values (in key order), keeping the PRIMARY KEY order of the rows. */
        private static Map<List<String>, List<String>> byKey(ReferenceDataModel m) {
            Map<List<String>, List<String>> rows = new java.util.LinkedHashMap<>();
            List<Integer> keyIdx = m.getPrimaryKey().stream().map(m::indexOf).toList();
            for (List<String> row : m.getRows()) {
                List<String> key = new ArrayList<>();
                for (int i : keyIdx) {
                    key.add(i < 0 || i >= row.size() ? null : row.get(i));
                }
                rows.put(key, row);
            }
            return rows;
        }

        private static boolean differs(ReferenceDataModel fm, List<String> fr, ReferenceDataModel tm, List<String> tr,
                                       List<String> shared) {
            for (String c : shared) {
                if (!Objects.equals(value(fm, fr, c), value(tm, tr, c))) {
                    return true;
                }
            }
            return false;
        }

        private static String value(ReferenceDataModel m, List<String> row, String column) {
            int i = m.indexOf(column);
            return i < 0 || i >= row.size() ? null : row.get(i);
        }

        /** {@code 'text'::type}, or NULL. */
        private static String typed(ReferenceDataModel m, String column, String value) {
            if (value == null) {
                return "NULL";
            }
            int i = m.indexOf(column);
            String type = i >= 0 && i < m.getColumnTypes().size() ? m.getColumnTypes().get(i) : null;
            return literal(value) + (type == null ? "" : "::" + type);
        }

        private static String keyCondition(ReferenceDataModel m, List<String> row) {
            List<String> parts = new ArrayList<>();
            for (String k : m.getPrimaryKey()) {
                parts.add(ident(k) + " = " + typed(m, k, value(m, row, k)));
            }
            return String.join(" AND ", parts);
        }

        private static String deleteRow(String tq, ReferenceDataModel m, List<String> row) {
            return "DELETE FROM " + tq + " WHERE " + keyCondition(m, row) + ";";
        }

        private static String upsertRow(String tq, ReferenceDataModel m, List<String> row, TableModel t) {
            List<String> cols = m.getColumns();
            boolean identityAlways = cols.stream().anyMatch(c -> {
                ColumnModel col = t.getColumns().get(c);
                return col != null && "ALWAYS".equals(col.getIdentityGeneration());
            });
            StringBuilder sb = new StringBuilder("INSERT INTO ").append(tq).append(" (")
                    .append(cols.stream().map(c -> ident(c)).collect(Collectors.joining(", "))).append(")");
            if (identityAlways) {
                sb.append(" OVERRIDING SYSTEM VALUE");
            }
            sb.append(" VALUES (");
            for (int i = 0; i < cols.size(); i++) {
                sb.append(i == 0 ? "" : ", ").append(typed(m, cols.get(i), i < row.size() ? row.get(i) : null));
            }
            sb.append(") ON CONFLICT (").append(m.getPrimaryKey().stream().map(c -> ident(c)).collect(Collectors.joining(", ")))
                    .append(")");
            List<String> rest = cols.stream().filter(c -> !m.getPrimaryKey().contains(c)).toList();
            if (rest.isEmpty()) {
                sb.append(" DO NOTHING;");
            } else {
                sb.append(" DO UPDATE SET ")
                        .append(rest.stream().map(c -> ident(c) + " = EXCLUDED." + ident(c)).collect(Collectors.joining(", ")))
                        .append(';');
            }
            return sb.toString();
        }

        private record AclEntry(String grantee, String privilege, boolean grantable) {
            static AclEntry parse(String entry) {
                int eq = entry.lastIndexOf('=');
                String grantee = eq < 0 ? "PUBLIC" : entry.substring(0, eq);
                String privilege = eq < 0 ? entry : entry.substring(eq + 1);
                boolean grantable = privilege.endsWith("*");
                return new AclEntry(grantee, grantable ? privilege.substring(0, privilege.length() - 1) : privilege,
                        grantable);
            }
        }
    }
}
