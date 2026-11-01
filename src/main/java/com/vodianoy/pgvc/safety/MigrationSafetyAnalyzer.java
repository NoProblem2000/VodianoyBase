package com.vodianoy.pgvc.safety;

import com.vodianoy.pgvc.diff.ChangeItem;
import com.vodianoy.pgvc.diff.SchemaDiffResult;
import com.vodianoy.pgvc.execution.MigrationStep;
import com.vodianoy.pgvc.execution.SqlScripts;
import com.vodianoy.pgvc.model.schema.ColumnModel;
import com.vodianoy.pgvc.model.schema.ConstraintModel;
import com.vodianoy.pgvc.model.schema.ConstraintType;
import com.vodianoy.pgvc.model.schema.DatabaseSnapshot;
import com.vodianoy.pgvc.model.schema.IndexModel;
import com.vodianoy.pgvc.model.schema.TableModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Migration Safety &amp; Lock Analyzer: for every statement of a script (or of every change of a diff) it works out
 * which table lock PostgreSQL takes, whether the table is scanned or rewritten, whether data is lost, how long it may
 * take from the table size statistics, how risky that is for a database in use, and - where one exists - a
 * zero-downtime pattern (NOT VALID + VALIDATE, CREATE INDEX CONCURRENTLY ...).
 *
 * <p>It is a heuristic over the statement text (no SQL parser): unknown statements are reported as SAFE with a note.
 * Texts are rendered in the language of the current request.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MigrationSafetyAnalyzer {

    /** A table above either limit is "big": long operations under blocking locks on it are DANGEROUS. */
    public static final long BIG_TABLE_BYTES = 100L * 1024 * 1024;
    public static final long BIG_TABLE_ROWS = 100_000;
    /** Below both limits a table counts as empty for the risk (nothing to scan or rewrite). */
    private static final long SOME_DATA_BYTES = 1024 * 1024;

    // rough throughput assumptions for the duration hint (bytes per second)
    private static final double SCAN_RATE = 400.0 * 1024 * 1024;
    private static final double REWRITE_RATE = 80.0 * 1024 * 1024;
    private static final double INDEX_RATE = 100.0 * 1024 * 1024;
    private static final double UPDATE_RATE = 30.0 * 1024 * 1024;

    private static final String IDENT = "(?:\"(?:[^\"]|\"\")+\"|[A-Za-z_][A-Za-z0-9_$]*)";
    private static final String QNAME = IDENT + "(?:\\s*\\.\\s*" + IDENT + ")?";
    private static final int F = Pattern.CASE_INSENSITIVE | Pattern.DOTALL;

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "^CREATE\\s+(?:(?:GLOBAL|LOCAL)\\s+)?(?:UNLOGGED\\s+|TEMP\\s+|TEMPORARY\\s+)?TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?("
                    + QNAME + ")(.*)$", F);
    private static final Pattern PARTITION_OF = Pattern.compile("\\bPARTITION\\s+OF\\s+(" + QNAME + ")", F);
    private static final Pattern DROP_TABLE = Pattern.compile("^DROP\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?(" + QNAME + ")(.*)$", F);
    private static final Pattern TRUNCATE = Pattern.compile("^TRUNCATE\\s+(?:TABLE\\s+)?(?:ONLY\\s+)?(" + QNAME + ")", F);
    private static final Pattern ALTER_TABLE = Pattern.compile(
            "^ALTER\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?(?:ONLY\\s+)?(" + QNAME + ")\\s*\\*?\\s+(.*)$", F);
    private static final Pattern CREATE_INDEX = Pattern.compile(
            "^CREATE\\s+(UNIQUE\\s+)?INDEX\\s+(CONCURRENTLY\\s+)?(?:IF\\s+NOT\\s+EXISTS\\s+)?(?:(" + IDENT
                    + ")\\s+)?ON\\s+(ONLY\\s+)?(" + QNAME + ")", F);
    private static final Pattern DROP_INDEX = Pattern.compile(
            "^DROP\\s+INDEX\\s+(CONCURRENTLY\\s+)?(?:IF\\s+EXISTS\\s+)?(" + QNAME + ")(.*)$", F);
    private static final Pattern VIEW_DDL = Pattern.compile(
            "^(?:CREATE\\s+(?:OR\\s+REPLACE\\s+)?(MATERIALIZED\\s+)?VIEW|DROP\\s+(MATERIALIZED\\s+)?VIEW|ALTER\\s+(MATERIALIZED\\s+)?VIEW)\\b.*", F);
    private static final Pattern REFRESH_MV = Pattern.compile("^REFRESH\\s+MATERIALIZED\\s+VIEW\\s+(CONCURRENTLY\\s+)?(" + QNAME + ")", F);
    private static final Pattern SEQUENCE_DDL = Pattern.compile("^(?:CREATE|ALTER|DROP)\\s+SEQUENCE\\b.*", F);
    private static final Pattern DROP_SCHEMA = Pattern.compile("^DROP\\s+SCHEMA\\b(.*)$", F);
    private static final Pattern UPDATE = Pattern.compile("^UPDATE\\s+(?:ONLY\\s+)?(" + QNAME + ")(.*)$", F);
    private static final Pattern DELETE = Pattern.compile("^DELETE\\s+FROM\\s+(?:ONLY\\s+)?(" + QNAME + ")(.*)$", F);
    private static final Pattern INSERT = Pattern.compile("^(?:INSERT\\s+INTO|COPY)\\s+(" + QNAME + ")", F);
    private static final Pattern VACUUM_FULL = Pattern.compile("^VACUUM\\s*(?:\\([^)]*\\bFULL\\b[^)]*\\)|\\s+FULL\\b)(.*)$", F);
    private static final Pattern VACUUM = Pattern.compile("^VACUUM\\b.*", F);
    private static final Pattern CLUSTER = Pattern.compile("^CLUSTER\\b(.*)$", F);
    private static final Pattern REINDEX = Pattern.compile("^REINDEX\\b.*", F);
    private static final Pattern HARMLESS = Pattern.compile(
            "^(?:CREATE|ALTER|DROP)\\s+(?:OR\\s+REPLACE\\s+)?(?:FUNCTION|PROCEDURE|TYPE|DOMAIN|EXTENSION|ROLE|USER|COLLATION|AGGREGATE|OPERATOR|SCHEMA)\\b.*"
                    + "|^(?:COMMENT|GRANT|REVOKE|SET|RESET|SELECT|ANALYZE|DO|NOTIFY|SHOW|EXPLAIN|VALUES)\\b.*", F);
    private static final Pattern TRIGGER_DDL = Pattern.compile("^(?:CREATE(?:\\s+OR\\s+REPLACE)?(?:\\s+CONSTRAINT)?\\s+TRIGGER|DROP\\s+TRIGGER)\\b.*", F);

    // ALTER TABLE actions
    private static final Pattern ADD_CONSTRAINT = Pattern.compile(
            "^ADD\\s+(?:CONSTRAINT\\s+(" + IDENT + ")\\s+)?(FOREIGN\\s+KEY|CHECK|PRIMARY\\s+KEY|UNIQUE|EXCLUDE)\\b(.*)$", F);
    private static final Pattern ADD_COLUMN = Pattern.compile("^ADD\\s+(?:COLUMN\\s+)?(?:IF\\s+NOT\\s+EXISTS\\s+)?(" + IDENT + ")\\s+(.*)$", F);
    private static final Pattern DROP_COLUMN = Pattern.compile("^DROP\\s+(?:COLUMN\\s+)?(?:IF\\s+EXISTS\\s+)?(" + IDENT + ")(.*)$", F);
    private static final Pattern ALTER_TYPE = Pattern.compile(
            "^ALTER\\s+(?:COLUMN\\s+)?(" + IDENT + ")\\s+(?:SET\\s+DATA\\s+)?TYPE\\s+(.+?)(?:\\s+COLLATE\\s+\\S+)?(?:\\s+USING\\s+(.*))?$", F);
    private static final Pattern SET_NOT_NULL = Pattern.compile("^ALTER\\s+(?:COLUMN\\s+)?(" + IDENT + ")\\s+SET\\s+NOT\\s+NULL$", F);
    private static final Pattern COLUMN_BRIEF = Pattern.compile(
            "^ALTER\\s+(?:COLUMN\\s+)?(" + IDENT + ")\\s+(?:DROP\\s+NOT\\s+NULL|SET\\s+DEFAULT\\b.*|DROP\\s+DEFAULT|DROP\\s+IDENTITY.*|ADD\\s+GENERATED\\b.*|SET\\s+GENERATED\\b.*|RESTART\\b.*|SET\\s+\\(.*|RESET\\s+\\(.*)$", F);
    private static final Pattern COLUMN_STATS = Pattern.compile("^ALTER\\s+(?:COLUMN\\s+)?(" + IDENT + ")\\s+SET\\s+(?:STATISTICS|STORAGE|COMPRESSION)\\b.*$", F);
    private static final Pattern VALIDATE = Pattern.compile("^VALIDATE\\s+CONSTRAINT\\s+(" + IDENT + ")$", F);
    private static final Pattern DROP_CONSTRAINT = Pattern.compile("^DROP\\s+CONSTRAINT\\s+(?:IF\\s+EXISTS\\s+)?(" + IDENT + ").*$", F);
    private static final Pattern RENAME = Pattern.compile("^RENAME\\b.*$", F);
    private static final Pattern ATTACH = Pattern.compile("^ATTACH\\s+PARTITION\\s+(" + QNAME + ")(.*)$", F);
    private static final Pattern DETACH = Pattern.compile("^DETACH\\s+PARTITION\\s+(" + QNAME + ")(\\s+CONCURRENTLY)?.*$", F);
    private static final Pattern REWRITE_ACTION = Pattern.compile("^SET\\s+(?:LOGGED|UNLOGGED|TABLESPACE\\b.*|ACCESS\\s+METHOD\\b.*)$", F);
    private static final Pattern TRIGGER_ACTION = Pattern.compile("^(?:ENABLE|DISABLE)\\s+(?:ALWAYS\\s+|REPLICA\\s+)?TRIGGER\\b.*$", F);
    private static final Pattern NOT_VALID = Pattern.compile("\\bNOT\\s+VALID\\s*$", F);
    private static final Pattern NOT_NULL = Pattern.compile("\\bNOT\\s+NULL\\b", F);
    private static final Pattern DEFAULT = Pattern.compile(
            "\\bDEFAULT\\s+(.+?)(?=\\s+(?:NOT\\s+NULL|NULL|CHECK|UNIQUE|PRIMARY|REFERENCES|CONSTRAINT|GENERATED|COLLATE)\\b|$)", F);
    private static final Pattern VOLATILE = Pattern.compile(
            "\\b(?:random|clock_timestamp|gen_random_uuid|uuid_generate_v[14]\\w*|nextval|timeofday|txid_current|statement_timestamp)\\s*\\(", F);
    private static final Pattern GENERATED_STORED = Pattern.compile("\\bGENERATED\\s+ALWAYS\\s+AS\\s*\\(", F);
    private static final Pattern IDENTITY = Pattern.compile("\\bGENERATED\\s+(?:ALWAYS|BY\\s+DEFAULT)\\s+AS\\s+IDENTITY\\b", F);
    private static final Pattern SERIAL = Pattern.compile("^(?:SMALL|BIG)?SERIAL\\d?\\b", F);
    private static final Pattern INLINE_INDEX = Pattern.compile("\\b(?:UNIQUE|PRIMARY\\s+KEY)\\b", F);
    private static final Pattern INLINE_CHECK = Pattern.compile("\\bCHECK\\s*\\(", F);
    private static final Pattern REFERENCES = Pattern.compile("\\bREFERENCES\\s+(" + QNAME + ")", F);
    private static final Pattern WHERE = Pattern.compile("\\bWHERE\\b", F);
    private static final Pattern CASCADE = Pattern.compile("\\bCASCADE\\b", F);
    private static final Pattern PAREN_COLUMNS = Pattern.compile("^\\s*\\(([^()]*)\\)\\s*(.*)$", F);

    private final MessageSource messages;

    private enum Work { NONE, SCAN, REWRITE, INDEX, UPDATE }

    /** Mutable analysis of one statement before it becomes a {@link SafetyReport}. */
    private static final class Effect {
        LockLevel lock;
        Work work = Work.NONE;
        boolean rewrite;
        boolean dataLoss;
        boolean nonTransactional;
        boolean newObject;
        RiskLevel atLeast = RiskLevel.SAFE;
        TableModel table;
        String tableName;
        final List<String> notes = new ArrayList<>();
        String alternative;

        void lock(LockLevel l) {
            lock = LockLevel.max(lock, l);
        }

        void work(Work w) {
            if (w.ordinal() > work.ordinal()) {
                work = w;
            }
        }

        void atLeast(RiskLevel r) {
            atLeast = atLeast.max(r);
        }
    }

    /** Lookup of the current schema plus what the analysed script creates on the way. */
    private static final class Context {
        final DatabaseSnapshot schema;
        final Set<String> created = new HashSet<>();

        Context(DatabaseSnapshot schema) {
            this.schema = schema == null ? DatabaseSnapshot.builder().build() : schema;
        }

        int serverVersion() {
            return schema.getServerVersion();
        }

        TableModel table(String key) {
            return key == null ? null : schema.getTables().get(key);
        }

        boolean isNew(String key) {
            return key != null && (created.contains(key) || !schema.getTables().containsKey(key));
        }

        /** {@code schema.name}: unqualified names resolve to the only table of that name (public preferred). */
        String resolve(String qname) {
            List<String> parts = splitName(qname);
            if (parts.size() == 2) {
                return parts.get(0) + "." + parts.get(1);
            }
            String name = parts.get(0);
            if (schema.getTables().containsKey("public." + name)) {
                return "public." + name;
            }
            for (TableModel t : schema.getTables().values()) {
                if (t.getName().equals(name)) {
                    return t.getQualifiedName();
                }
            }
            for (String c : created) {
                if (c.endsWith("." + name)) {
                    return c;
                }
            }
            return "public." + name;
        }

        /** Table of an index by index name. */
        String tableOfIndex(String qname) {
            List<String> parts = splitName(qname);
            String name = parts.get(parts.size() - 1);
            String schemaName = parts.size() == 2 ? parts.get(0) : null;
            for (TableModel t : schema.getTables().values()) {
                if (schemaName != null && !schemaName.equals(t.getSchemaName())) {
                    continue;
                }
                IndexModel i = t.getIndexes().get(name);
                if (i != null) {
                    return t.getQualifiedName();
                }
            }
            return null;
        }
    }

    // ------------------------------------------------------------------ API

    /** Safety of an arbitrary script against the given schema (with its size statistics). */
    public SqlSafetyReport analyzeSql(String sql, DatabaseSnapshot schema) {
        Context ctx = new Context(schema);
        List<MigrationStep> steps = MigrationStep.plan(sql == null ? "" : sql);
        List<StatementSafety> statements = new ArrayList<>();
        List<SafetyReport> reports = new ArrayList<>();
        int index = 0;
        for (MigrationStep step : steps) {
            for (String statement : step.statements()) {
                SafetyReport r = analyzeStatement(statement, ctx);
                if (r.getZeroDowntimeAlternativeSql() != null) {
                    r.getAlternatives().add(new SqlAlternative(statement, r.getZeroDowntimeAlternativeSql()));
                }
                statements.add(new StatementSafety(++index, step.index(), statement, r));
                reports.add(r);
            }
        }
        SafetyReport overall = aggregate(reports, sql);
        attachAlternatives(overall, statements.stream().map(StatementSafety::sql).toList(), reports, sql);
        return new SqlSafetyReport(overall, statements, steps.size(), MigrationStep.isMultiStep(steps));
    }

    /**
     * Sets {@link ChangeItem#getSafety()} of every change from its UP SQL.
     *
     * @param schema the database the UP migration would run on (structure and size statistics)
     */
    public void annotate(SchemaDiffResult diff, DatabaseSnapshot schema) {
        if (diff == null) {
            return;
        }
        Context ctx = new Context(schema);
        for (ChangeItem item : diff.getChanges()) {
            if (item.getUpSql() == null || item.getUpSql().isBlank()) {
                continue;
            }
            List<String> statements = SqlScripts.statements(item.getUpSql());
            List<SafetyReport> reports = new ArrayList<>();
            for (String statement : statements) {
                reports.add(analyzeStatement(statement, ctx));
            }
            SafetyReport aggregated = aggregate(reports, item.getUpSql());
            attachAlternatives(aggregated, statements, reports, item.getUpSql());
            item.setSafety(aggregated);
        }
    }

    /** Copy of {@code structure} whose tables carry the size statistics of {@code sizes} (matched by name). */
    public static DatabaseSnapshot withSizes(DatabaseSnapshot structure, DatabaseSnapshot sizes) {
        if (structure == null) {
            return sizes;
        }
        if (sizes == null) {
            return structure;
        }
        DatabaseSnapshot copy = structure.toBuilder().tables(new java.util.LinkedHashMap<>()).build();
        for (Map.Entry<String, TableModel> e : structure.getTables().entrySet()) {
            TableModel s = sizes.getTables().get(e.getKey());
            TableModel t = s == null ? e.getValue() : e.getValue().toBuilder().estimatedRows(s.getEstimatedRows())
                    .totalSizeBytes(s.getTotalSizeBytes()).prettySize(s.getPrettySize()).build();
            copy.getTables().put(e.getKey(), t);
        }
        // tables that exist only in the sizes snapshot (renamed / created since): keep them for lookups
        for (Map.Entry<String, TableModel> e : sizes.getTables().entrySet()) {
            copy.getTables().putIfAbsent(e.getKey(), e.getValue());
        }
        if (copy.getServerVersion() == 0) {
            copy.setServerVersion(sizes.getServerVersion());
        }
        return copy;
    }

    // ------------------------------------------------------------------ statements

    SafetyReport analyzeStatement(String statement, Context ctx) {
        Effect e = new Effect();
        String n = SqlScripts.normalize(statement);
        try {
            classify(n, statement, ctx, e);
        } catch (RuntimeException ex) {
            log.debug("Cannot analyse statement {}", n, ex);
            e.notes.add(text("safety.note.notAnalysed"));
        }
        return toReport(e, ctx);
    }

    private void classify(String n, String original, Context ctx, Effect e) {
        Matcher m;
        if ((m = CREATE_TABLE.matcher(n)).matches()) {
            String key = ctx.resolve(m.group(1));
            Matcher p = PARTITION_OF.matcher(m.group(2));
            ctx.created.add(key);
            if (p.find()) {
                String parent = ctx.resolve(p.group(1));
                e.tableName = parent;
                e.table = ctx.table(parent);
                e.lock(LockLevel.ACCESS_EXCLUSIVE);
                e.notes.add(text("safety.note.partitionOf", parent));
            } else {
                e.newObject = true;
                e.tableName = key;
                if (Pattern.compile("\\bAS\\s+(?:SELECT|TABLE|VALUES|WITH)\\b", F).matcher(m.group(2)).find()) {
                    e.notes.add(text("safety.note.createTableAs"));
                }
            }
        } else if ((m = DROP_TABLE.matcher(n)).matches()) {
            target(e, ctx, ctx.resolve(m.group(1)));
            e.lock(LockLevel.ACCESS_EXCLUSIVE);
            e.dataLoss = true;
            e.notes.add(text("safety.note.dropTable"));
        } else if ((m = TRUNCATE.matcher(n)).find()) {
            target(e, ctx, ctx.resolve(m.group(1)));
            e.lock(LockLevel.ACCESS_EXCLUSIVE);
            e.dataLoss = true;
            e.notes.add(text("safety.note.truncate"));
        } else if ((m = ALTER_TABLE.matcher(n)).matches()) {
            String key = ctx.resolve(m.group(1));
            target(e, ctx, key);
            List<String> actions = splitTopLevel(m.group(2));
            for (String action : actions) {
                alterAction(action.strip(), m.group(1), key, ctx, e, actions.size() == 1 ? original : null);
            }
        } else if ((m = CREATE_INDEX.matcher(n)).find()) {
            String key = ctx.resolve(m.group(5));
            target(e, ctx, key);
            boolean concurrently = m.group(2) != null;
            if (concurrently) {
                e.lock(LockLevel.SHARE_UPDATE_EXCLUSIVE);
                e.work(Work.INDEX);
                e.nonTransactional = true;
                e.notes.add(text("safety.note.concurrently"));
            } else if (!ctx.isNew(key)) {
                e.lock(LockLevel.SHARE);
                e.work(Work.INDEX);
                e.notes.add(text("safety.note.createIndex"));
                boolean partitioned = e.table != null && e.table.isPartitioned();
                if (partitioned) {
                    e.notes.add(text("safety.note.partitionedIndex"));
                } else if (m.group(4) == null && hasData(e.table)) {
                    e.alternative = step(Pattern.compile("(?i)^(\\s*CREATE\\s+(?:UNIQUE\\s+)?INDEX)\\s+")
                            .matcher(original.strip()).replaceFirst("$1 CONCURRENTLY "));
                }
            } else {
                e.newObject = true;
            }
        } else if ((m = DROP_INDEX.matcher(n)).matches()) {
            String key = ctx.tableOfIndex(m.group(2));
            target(e, ctx, key);
            if (m.group(1) != null) {
                e.lock(LockLevel.SHARE_UPDATE_EXCLUSIVE);
                e.nonTransactional = true;
                e.notes.add(text("safety.note.concurrently"));
            } else {
                e.lock(LockLevel.ACCESS_EXCLUSIVE);
                if (key != null && big(e.table) && !e.table.isPartitioned() && !CASCADE.matcher(m.group(3)).find()) {
                    e.alternative = step(Pattern.compile("(?i)^(\\s*DROP\\s+INDEX)\\s+").matcher(original.strip())
                            .replaceFirst("$1 CONCURRENTLY "));
                }
            }
        } else if ((m = REFRESH_MV.matcher(n)).find()) {
            if (m.group(1) != null) {
                e.lock(LockLevel.EXCLUSIVE);
                e.notes.add(text("safety.note.refreshConcurrently"));
            } else {
                e.lock(LockLevel.ACCESS_EXCLUSIVE);
                e.atLeast(RiskLevel.WARNING);
                e.notes.add(text("safety.note.refresh"));
            }
            e.work(Work.REWRITE);
        } else if (VIEW_DDL.matcher(n).matches()) {
            e.newObject = true;
            e.notes.add(text("safety.note.view"));
        } else if (SEQUENCE_DDL.matcher(n).matches()) {
            e.newObject = true;
        } else if ((m = DROP_SCHEMA.matcher(n)).matches()) {
            e.lock(LockLevel.ACCESS_EXCLUSIVE);
            if (CASCADE.matcher(m.group(1)).find()) {
                e.dataLoss = true;
                e.notes.add(text("safety.note.dropSchemaCascade"));
            }
        } else if ((m = UPDATE.matcher(n)).matches()) {
            target(e, ctx, ctx.resolve(m.group(1)));
            e.lock(LockLevel.ROW_EXCLUSIVE);
            e.work(Work.UPDATE);
            if (big(e.table)) {
                e.atLeast(RiskLevel.WARNING);
            }
            e.notes.add(text(WHERE.matcher(m.group(2)).find() ? "safety.note.update" : "safety.note.updateAll"));
        } else if ((m = DELETE.matcher(n)).matches()) {
            target(e, ctx, ctx.resolve(m.group(1)));
            e.lock(LockLevel.ROW_EXCLUSIVE);
            e.work(Work.UPDATE);
            e.dataLoss = true;
            e.notes.add(text(WHERE.matcher(m.group(2)).find() ? "safety.note.delete" : "safety.note.deleteAll"));
        } else if ((m = INSERT.matcher(n)).find()) {
            target(e, ctx, ctx.resolve(m.group(1)));
            e.lock(LockLevel.ROW_EXCLUSIVE);
        } else if ((m = VACUUM_FULL.matcher(n)).matches() || (m = CLUSTER.matcher(n)).matches()) {
            e.lock(LockLevel.ACCESS_EXCLUSIVE);
            e.work(Work.REWRITE);
            e.rewrite = true;
            e.notes.add(text("safety.note.vacuumFull"));
        } else if (VACUUM.matcher(n).matches()) {
            e.lock(LockLevel.SHARE_UPDATE_EXCLUSIVE);
            e.nonTransactional = true;
        } else if (REINDEX.matcher(n).matches()) {
            if (n.toUpperCase(Locale.ROOT).contains("CONCURRENTLY")) {
                e.lock(LockLevel.SHARE_UPDATE_EXCLUSIVE);
                e.nonTransactional = true;
            } else {
                e.lock(LockLevel.ACCESS_EXCLUSIVE);
                e.work(Work.INDEX);
                e.atLeast(RiskLevel.WARNING);
                e.notes.add(text("safety.note.reindex"));
            }
        } else if (TRIGGER_DDL.matcher(n).matches()) {
            e.lock(LockLevel.SHARE_ROW_EXCLUSIVE);
        } else if (HARMLESS.matcher(n).matches()) {
            e.newObject = true;
        } else {
            e.notes.add(text("safety.note.notAnalysed"));
        }
    }

    private void alterAction(String action, String rawTable, String key, Context ctx, Effect e, String original) {
        Matcher m;
        boolean newTable = ctx.isNew(key);
        if ((m = ADD_CONSTRAINT.matcher(action)).matches()) {
            String name = m.group(1);
            String kind = m.group(2).toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
            String rest = m.group(3);
            boolean notValid = NOT_VALID.matcher(action).find();
            switch (kind) {
                case "FOREIGN KEY" -> {
                    e.lock(LockLevel.SHARE_ROW_EXCLUSIVE);
                    Matcher ref = REFERENCES.matcher(rest);
                    if (ref.find()) {
                        e.notes.add(text("safety.note.fkReferenced", ctx.resolve(ref.group(1))));
                    }
                    if (!notValid && !newTable) {
                        e.work(Work.SCAN);
                        e.notes.add(text("safety.note.fkScan"));
                        if (name != null && original != null) {
                            e.alternative = notValidPattern(original, rawTable, name);
                        }
                    }
                }
                case "CHECK" -> {
                    e.lock(LockLevel.ACCESS_EXCLUSIVE);
                    if (!notValid && !newTable) {
                        e.work(Work.SCAN);
                        e.notes.add(text("safety.note.checkScan"));
                        if (name != null && original != null) {
                            e.alternative = notValidPattern(original, rawTable, name);
                        }
                    }
                }
                case "PRIMARY KEY", "UNIQUE" -> {
                    e.lock(LockLevel.ACCESS_EXCLUSIVE);
                    if (!Pattern.compile("^\\s*USING\\s+INDEX\\b", F).matcher(rest).find() && !newTable) {
                        e.work(Work.INDEX);
                        e.notes.add(text("safety.note.uniqueIndex"));
                        Matcher cols = PAREN_COLUMNS.matcher(rest);
                        if (name != null && original != null && cols.matches() && cols.group(2).isBlank()
                                && e.table != null && !e.table.isPartitioned()) {
                            String t = rawTable.strip();
                            e.alternative = SqlScripts.STEP_MARKER + "\nCREATE UNIQUE INDEX CONCURRENTLY " + name + " ON " + t
                                    + " (" + cols.group(1).strip() + ");\n" + SqlScripts.STEP_MARKER + "\nALTER TABLE " + t
                                    + " ADD CONSTRAINT " + name + " " + kind + " USING INDEX " + name + ";";
                        }
                    }
                }
                default -> {
                    e.lock(LockLevel.ACCESS_EXCLUSIVE);
                    if (!newTable) {
                        e.work(Work.INDEX);
                    }
                }
            }
        } else if ((m = DROP_COLUMN.matcher(action)).matches() && !action.toUpperCase(Locale.ROOT).startsWith("DROP CONSTRAINT")) {
            e.lock(LockLevel.ACCESS_EXCLUSIVE);
            e.dataLoss = true;
            e.notes.add(text("safety.note.dropColumn", unquote(m.group(1))));
        } else if ((m = ADD_COLUMN.matcher(action)).matches()) {
            addColumn(m.group(1), m.group(2), rawTable, key, newTable, ctx, e, original);
        } else if ((m = ALTER_TYPE.matcher(action)).matches()) {
            alterType(unquote(m.group(1)), m.group(2).strip(), m.group(3), newTable, e);
        } else if ((m = SET_NOT_NULL.matcher(action)).matches()) {
            e.lock(LockLevel.ACCESS_EXCLUSIVE);
            String column = unquote(m.group(1));
            if (!newTable && !hasValidNotNullCheck(e.table, column)) {
                e.work(Work.SCAN);
                e.notes.add(text("safety.note.setNotNull"));
                if (original != null && ctx.serverVersion() >= 120000) {
                    String c = checkName(e.table == null ? key : e.table.getName(), column);
                    String t = rawTable.strip();
                    String col = m.group(1);
                    e.alternative = "ALTER TABLE " + t + " ADD CONSTRAINT " + c + " CHECK (" + col + " IS NOT NULL) NOT VALID;\n"
                            + SqlScripts.STEP_MARKER + "\nALTER TABLE " + t + " VALIDATE CONSTRAINT " + c + ";\n"
                            + "-- " + text("safety.alt.setNotNullUsesCheck") + "\n"
                            + original.strip() + (original.strip().endsWith(";") ? "" : ";") + "\n"
                            + "ALTER TABLE " + t + " DROP CONSTRAINT " + c + ";";
                }
            }
        } else if (COLUMN_STATS.matcher(action).matches()) {
            e.lock(LockLevel.SHARE_UPDATE_EXCLUSIVE);
        } else if (COLUMN_BRIEF.matcher(action).matches()) {
            e.lock(LockLevel.ACCESS_EXCLUSIVE);
        } else if (VALIDATE.matcher(action).matches()) {
            e.lock(LockLevel.SHARE_UPDATE_EXCLUSIVE);
            e.work(Work.SCAN);
            e.notes.add(text("safety.note.validate"));
        } else if (DROP_CONSTRAINT.matcher(action).matches()) {
            e.lock(LockLevel.ACCESS_EXCLUSIVE);
        } else if (RENAME.matcher(action).matches()) {
            e.lock(LockLevel.ACCESS_EXCLUSIVE);
            if (!newTable) {
                e.atLeast(RiskLevel.WARNING);
                e.notes.add(text("safety.note.rename"));
            }
        } else if ((m = ATTACH.matcher(action)).matches()) {
            String partition = ctx.resolve(m.group(1));
            e.lock(ctx.serverVersion() >= 120000 ? LockLevel.SHARE_UPDATE_EXCLUSIVE : LockLevel.ACCESS_EXCLUSIVE);
            TableModel p = ctx.table(partition);
            if (p != null) {
                e.table = p;
                e.tableName = partition;
            }
            e.work(Work.SCAN);
            e.notes.add(text("safety.note.attach", partition));
        } else if ((m = DETACH.matcher(action)).matches()) {
            if (m.group(2) != null) {
                e.lock(LockLevel.SHARE_UPDATE_EXCLUSIVE);
                e.nonTransactional = true;
            } else {
                e.lock(LockLevel.ACCESS_EXCLUSIVE);
                if (ctx.serverVersion() >= 140000) {
                    e.notes.add(text("safety.note.detachConcurrently"));
                }
            }
        } else if (REWRITE_ACTION.matcher(action).matches()) {
            e.lock(LockLevel.ACCESS_EXCLUSIVE);
            e.work(Work.REWRITE);
            e.rewrite = true;
        } else if (TRIGGER_ACTION.matcher(action).matches()) {
            e.lock(LockLevel.SHARE_ROW_EXCLUSIVE);
        } else {
            // OWNER TO, SET SCHEMA, SET (storage parameters), REPLICA IDENTITY, INHERIT ...: brief, but exclusive
            e.lock(LockLevel.ACCESS_EXCLUSIVE);
        }
    }

    private void addColumn(String rawColumn, String definition, String rawTable, String key, boolean newTable,
                           Context ctx, Effect e, String original) {
        e.lock(LockLevel.ACCESS_EXCLUSIVE);
        String column = unquote(rawColumn);
        Matcher d = DEFAULT.matcher(definition);
        String defaultExpr = d.find() ? d.group(1) : null;
        boolean volatileDefault = defaultExpr != null && VOLATILE.matcher(defaultExpr).find();
        boolean generated = GENERATED_STORED.matcher(definition).find();
        boolean identity = IDENTITY.matcher(definition).find();
        boolean serial = SERIAL.matcher(definition.strip()).find();
        if (newTable) {
            return;
        }
        if (volatileDefault || generated || identity || serial) {
            e.work(Work.REWRITE);
            e.rewrite = true;
            e.notes.add(text("safety.note.addColumnRewrite", column));
        } else if (defaultExpr != null && ctx.serverVersion() > 0 && ctx.serverVersion() < 110000) {
            e.work(Work.REWRITE);
            e.rewrite = true;
            e.notes.add(text("safety.note.addColumnDefaultOld", column));
        }
        if (INLINE_INDEX.matcher(definition).find()) {
            e.work(Work.INDEX);
        }
        if (INLINE_CHECK.matcher(definition).find()) {
            e.work(Work.SCAN);
        }
        boolean notNull = NOT_NULL.matcher(definition).find();
        if (notNull && defaultExpr == null && !identity && !serial && !generated) {
            if (hasData(e.table)) {
                e.atLeast(RiskLevel.WARNING);
                e.notes.add(text("safety.note.addNotNullFails", column));
            }
            if (original != null) {
                String t = rawTable.strip();
                String c = checkName(e.table == null ? key.substring(key.indexOf('.') + 1) : e.table.getName(), column);
                String nullable = NOT_NULL.matcher(definition).replaceFirst("").replaceAll("\\s{2,}", " ").strip();
                StringBuilder alt = new StringBuilder();
                alt.append("ALTER TABLE ").append(t).append(" ADD COLUMN ").append(rawColumn).append(' ').append(nullable).append(";\n")
                        .append("-- ").append(text("safety.alt.backfill")).append('\n')
                        .append("-- UPDATE ").append(t).append(" SET ").append(rawColumn).append(" = ... WHERE ").append(rawColumn)
                        .append(" IS NULL;\n")
                        .append("ALTER TABLE ").append(t).append(" ADD CONSTRAINT ").append(c).append(" CHECK (")
                        .append(rawColumn).append(" IS NOT NULL) NOT VALID;\n")
                        .append(SqlScripts.STEP_MARKER).append('\n')
                        .append("ALTER TABLE ").append(t).append(" VALIDATE CONSTRAINT ").append(c).append(';');
                if (ctx.serverVersion() >= 120000) {
                    alt.append("\n-- ").append(text("safety.alt.setNotNullUsesCheck")).append('\n')
                            .append("ALTER TABLE ").append(t).append(" ALTER COLUMN ").append(rawColumn).append(" SET NOT NULL;\n")
                            .append("ALTER TABLE ").append(t).append(" DROP CONSTRAINT ").append(c).append(';');
                }
                e.alternative = alt.toString();
            }
        }
    }

    private void alterType(String column, String newType, String using, boolean newTable, Effect e) {
        e.lock(LockLevel.ACCESS_EXCLUSIVE);
        if (newTable) {
            return;
        }
        ColumnModel c = e.table == null ? null : e.table.getColumns().get(column);
        String oldType = c == null ? null : c.getDataType();
        boolean trivialUsing = using == null || using.strip().matches("(?is)\\(?\\s*\"?" + Pattern.quote(column)
                + "\"?\\s*\\)?\\s*::\\s*.+");
        TypeChange change = oldType == null ? TypeChange.UNKNOWN : TypeChange.of(oldType, newType);
        if (change == TypeChange.SAME || (change == TypeChange.COERCIBLE && trivialUsing)) {
            e.notes.add(text("safety.note.typeNoRewrite", column));
            return;
        }
        e.work(Work.REWRITE);
        e.rewrite = true;
        e.notes.add(text("safety.note.typeRewrite", column, oldType == null ? "?" : oldType, newType));
        if (change == TypeChange.NARROWING) {
            e.dataLoss = true;
            e.notes.add(text("safety.note.typeNarrowing", column));
        } else if (change == TypeChange.UNKNOWN || !trivialUsing) {
            e.atLeast(RiskLevel.WARNING);
        }
    }

    /** Coarse classification of {@code ALTER COLUMN TYPE}. */
    enum TypeChange {
        SAME, COERCIBLE, WIDENING, NARROWING, UNKNOWN;

        private static final Pattern VARCHAR = Pattern.compile("(?i)^(?:character varying|varchar)(?:\\((\\d+)\\))?$");
        private static final Pattern CHAR = Pattern.compile("(?i)^(?:character|char|bpchar)(?:\\((\\d+)\\))?$");
        private static final Pattern NUMERIC = Pattern.compile("(?i)^(?:numeric|decimal)(?:\\((\\d+)(?:\\s*,\\s*(\\d+))?\\))?$");
        private static final List<String> INTEGERS = List.of("smallint", "integer", "bigint");

        static TypeChange of(String oldType, String newType) {
            String a = canonical(oldType);
            String b = canonical(newType);
            if (a.equals(b)) {
                return SAME;
            }
            Matcher va = VARCHAR.matcher(a);
            Matcher vb = VARCHAR.matcher(b);
            if (va.matches() && (b.equals("text") || vb.matches())) {
                if (b.equals("text") || vb.group(1) == null) {
                    return COERCIBLE;
                }
                if (va.group(1) == null) {
                    return NARROWING;
                }
                return Integer.parseInt(vb.group(1)) >= Integer.parseInt(va.group(1)) ? COERCIBLE : NARROWING;
            }
            if (a.equals("text") && vb.matches()) {
                return vb.group(1) == null ? COERCIBLE : NARROWING;
            }
            if (CHAR.matcher(a).matches() && (b.equals("text") || vb.matches())) {
                return WIDENING;
            }
            Matcher na = NUMERIC.matcher(a);
            Matcher nb = NUMERIC.matcher(b);
            if (na.matches() && nb.matches()) {
                if (nb.group(1) == null) {
                    return COERCIBLE;
                }
                if (na.group(1) == null) {
                    return NARROWING;
                }
                int pa = Integer.parseInt(na.group(1));
                int pb = Integer.parseInt(nb.group(1));
                int sa = na.group(2) == null ? 0 : Integer.parseInt(na.group(2));
                int sb = nb.group(2) == null ? 0 : Integer.parseInt(nb.group(2));
                if (sa == sb && pb >= pa) {
                    return COERCIBLE;
                }
                return pb - sb >= pa - sa && sb >= sa ? WIDENING : NARROWING;
            }
            int ia = INTEGERS.indexOf(a);
            int ib = INTEGERS.indexOf(b);
            if (ia >= 0 && ib >= 0) {
                return ib > ia ? WIDENING : NARROWING;
            }
            if (ia >= 0 && (nb.matches() || b.equals("double precision") || b.equals("real"))) {
                return WIDENING;
            }
            if ((a.equals("double precision") || a.equals("real") || na.matches()) && ib >= 0) {
                return NARROWING;
            }
            if (a.equals("double precision") && b.equals("real")) {
                return NARROWING;
            }
            if (a.startsWith("timestamp") && b.equals("date")) {
                return NARROWING;
            }
            if (b.equals("text")) {
                return WIDENING;
            }
            if (b.equals("boolean") || b.equals("date") || ib >= 0) {
                return NARROWING;
            }
            return UNKNOWN;
        }

        private static String canonical(String type) {
            String t = type.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").replaceAll("\\s*\\(\\s*", "(")
                    .replaceAll("\\s*\\)", ")").replaceAll("\\s*,\\s*", ",");
            return switch (t) {
                case "int", "int4" -> "integer";
                case "int8" -> "bigint";
                case "int2" -> "smallint";
                case "float8", "float" -> "double precision";
                case "float4" -> "real";
                case "bool" -> "boolean";
                case "timestamptz" -> "timestamp with time zone";
                case "timestamp" -> "timestamp without time zone";
                default -> t.replace("varchar", "character varying").replace("decimal", "numeric");
            };
        }
    }

    // ------------------------------------------------------------------ risk

    private SafetyReport toReport(Effect e, Context ctx) {
        TableModel t = e.table;
        boolean big = big(t);
        boolean data = hasData(t);
        RiskLevel risk = e.atLeast;
        if (e.dataLoss) {
            risk = RiskLevel.DANGEROUS;
        } else if (e.work != Work.NONE && t != null) {
            if (e.lock != null && e.lock.blocksWrites()) {
                risk = risk.max(big ? RiskLevel.DANGEROUS : data ? RiskLevel.WARNING : RiskLevel.SAFE);
            }
            if (big && e.lock == LockLevel.ROW_EXCLUSIVE) {
                risk = risk.max(RiskLevel.WARNING);
            }
        } else if (e.lock == LockLevel.ACCESS_EXCLUSIVE && big) {
            risk = risk.max(RiskLevel.WARNING);
            e.notes.add(text("safety.note.briefLockBigTable"));
        }
        if (e.lock != null && e.lock.blocksWrites() && e.work != Work.NONE && big) {
            e.notes.add(text(e.lock.blocksReads() ? "safety.note.blocksAll" : "safety.note.blocksWrites"));
        }

        Double seconds = null;
        String hint = null;
        if (t != null && e.work != Work.NONE && t.getTotalSizeBytes() > 0) {
            double rate = switch (e.work) {
                case SCAN -> SCAN_RATE;
                case REWRITE -> REWRITE_RATE;
                case INDEX -> INDEX_RATE;
                case UPDATE -> UPDATE_RATE;
                default -> SCAN_RATE;
            };
            seconds = Math.max(0.1, t.getTotalSizeBytes() / rate);
            hint = text("safety.duration." + e.work.name().toLowerCase(Locale.ROOT), formatSeconds(seconds),
                    sizeText(t), rowsText(t.getEstimatedRows()));
        } else if (e.work == Work.NONE && e.lock != null && !e.newObject) {
            hint = text("safety.duration.instant");
        }

        SafetyReport.SafetyReportBuilder r = SafetyReport.builder()
                .riskLevel(risk)
                .lockType(e.newObject && e.lock == null ? null : e.lock)
                .causesTableRewrite(e.rewrite)
                .causesDataLoss(e.dataLoss)
                .longRunning(e.work != Work.NONE)
                .nonTransactional(e.nonTransactional)
                .table(e.tableName)
                .estimatedSeconds(seconds)
                .estimatedDurationHint(hint)
                .notes(new ArrayList<>(new LinkedHashSet<>(e.notes)));
        if (t != null) {
            r.estimatedRows(t.getEstimatedRows()).totalSizeBytes(t.getTotalSizeBytes()).prettySize(t.getPrettySize());
        }
        if (e.alternative != null && risk != RiskLevel.SAFE) {
            r.zeroDowntimeAlternativeSql(e.alternative);
        }
        return r.build();
    }

    /** Worst of several statement reports; alternatives are kept per statement. */
    private SafetyReport aggregate(List<SafetyReport> reports, String sourceSql) {
        if (reports.isEmpty()) {
            return SafetyReport.builder().build();
        }
        SafetyReport worst = reports.get(0);
        RiskLevel risk = RiskLevel.SAFE;
        LockLevel lock = null;
        boolean rewrite = false;
        boolean loss = false;
        boolean longRunning = false;
        boolean nonTx = false;
        double seconds = 0;
        boolean anySeconds = false;
        Set<String> notes = new LinkedHashSet<>();
        for (SafetyReport r : reports) {
            if (r.getRiskLevel().ordinal() > risk.ordinal()
                    || (r.getRiskLevel() == risk && r.getTotalSizeBytes() != null && worst.getTotalSizeBytes() == null)) {
                worst = r;
            }
            risk = risk.max(r.getRiskLevel());
            lock = LockLevel.max(lock, r.getLockType());
            rewrite |= r.isCausesTableRewrite();
            loss |= r.isCausesDataLoss();
            longRunning |= r.isLongRunning();
            nonTx |= r.isNonTransactional();
            if (r.getEstimatedSeconds() != null) {
                seconds += r.getEstimatedSeconds();
                anySeconds = true;
            }
            notes.addAll(r.getNotes());
        }
        SafetyReport source = worst.getTable() != null ? worst
                : reports.stream().filter(r -> r.getTable() != null).findFirst().orElse(worst);
        return SafetyReport.builder()
                .riskLevel(risk)
                .lockType(lock)
                .causesTableRewrite(rewrite)
                .causesDataLoss(loss)
                .longRunning(longRunning)
                .nonTransactional(nonTx)
                .table(source.getTable())
                .estimatedRows(source.getEstimatedRows())
                .totalSizeBytes(source.getTotalSizeBytes())
                .prettySize(source.getPrettySize())
                .estimatedSeconds(anySeconds ? seconds : null)
                .estimatedDurationHint(worst.getEstimatedDurationHint())
                .notes(new ArrayList<>(notes))
                .build();
    }

    /** Fills {@link SafetyReport#getAlternatives()} of an aggregated report from its statements. */
    private void attachAlternatives(SafetyReport aggregated, List<String> statements, List<SafetyReport> reports,
                                    String sourceSql) {
        String combined = sourceSql;
        for (int i = 0; i < statements.size(); i++) {
            String alt = reports.get(i).getZeroDowntimeAlternativeSql();
            if (alt != null) {
                aggregated.getAlternatives().add(new SqlAlternative(statements.get(i), alt));
                if (combined != null) {
                    combined = combined.replace(statements.get(i), alt);
                }
            }
        }
        if (!aggregated.getAlternatives().isEmpty()) {
            aggregated.setZeroDowntimeAlternativeSql(combined);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void target(Effect e, Context ctx, String key) {
        e.tableName = key;
        boolean created = key != null && ctx.created.contains(key);
        e.table = key == null || created ? null : ctx.table(key);
        if (created) {
            e.newObject = true;
        }
    }

    private static boolean big(TableModel t) {
        return t != null && (t.getTotalSizeBytes() > BIG_TABLE_BYTES || t.getEstimatedRows() > BIG_TABLE_ROWS);
    }

    private static boolean hasData(TableModel t) {
        return t != null && (t.getEstimatedRows() > 0 || t.getTotalSizeBytes() > SOME_DATA_BYTES);
    }

    private static boolean hasValidNotNullCheck(TableModel t, String column) {
        if (t == null) {
            return false;
        }
        for (ConstraintModel c : t.getConstraints().values()) {
            if (c.getType() == ConstraintType.CHECK && c.isValidated() && c.getDefinition() != null
                    && c.getDefinition().replace("\"", "").toLowerCase(Locale.ROOT)
                    .contains("(" + column.toLowerCase(Locale.ROOT) + " is not null)")) {
                return true;
            }
        }
        return false;
    }

    /** {@code -- pgvc:step} / statement / {@code -- pgvc:step}: the statement becomes a step of its own. */
    private static String step(String statement) {
        String s = statement.strip();
        return SqlScripts.STEP_MARKER + "\n" + s + (s.endsWith(";") ? "" : ";") + "\n" + SqlScripts.STEP_MARKER;
    }

    /** ADD CONSTRAINT ... NOT VALID now, VALIDATE CONSTRAINT in the next step (no long blocking lock). */
    private static String notValidPattern(String original, String rawTable, String constraint) {
        String s = original.strip();
        if (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).stripTrailing();
        }
        return s + " NOT VALID;\n" + SqlScripts.STEP_MARKER + "\nALTER TABLE " + rawTable.strip()
                + " VALIDATE CONSTRAINT " + constraint + ";";
    }

    private static String checkName(String table, String column) {
        String name = table + "_" + column + "_not_null";
        name = name.length() > 63 ? name.substring(0, 63) : name;
        return name.matches("[a-z_][a-z0-9_$]*") ? name : "\"" + name.replace("\"", "\"\"") + "\"";
    }

    /** Splits ALTER TABLE actions at commas outside of parentheses and quotes. */
    static List<String> splitTopLevel(String s) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        boolean single = false;
        boolean dbl = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' && !dbl) {
                single = !single;
            } else if (c == '"' && !single) {
                dbl = !dbl;
            } else if (!single && !dbl) {
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                } else if (c == ',' && depth == 0) {
                    parts.add(s.substring(start, i));
                    start = i + 1;
                }
            }
        }
        parts.add(s.substring(start));
        return parts;
    }

    static List<String> splitName(String qname) {
        List<String> parts = new ArrayList<>();
        Matcher m = Pattern.compile(IDENT).matcher(qname);
        while (m.find()) {
            parts.add(unquote(m.group()));
        }
        return parts.size() > 2 ? parts.subList(parts.size() - 2, parts.size()) : parts;
    }

    static String unquote(String ident) {
        String s = ident.strip();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            return s.substring(1, s.length() - 1).replace("\"\"", "\"");
        }
        return s.toLowerCase(Locale.ROOT);
    }

    private String sizeText(TableModel t) {
        return t.getPrettySize() == null ? t.getTotalSizeBytes() + " bytes" : t.getPrettySize();
    }

    static String rowsText(long rows) {
        if (rows >= 1_000_000) {
            return String.format(Locale.ROOT, "%.1fM", rows / 1_000_000.0);
        }
        if (rows >= 1_000) {
            return String.format(Locale.ROOT, "%.0fk", rows / 1_000.0);
        }
        return Long.toString(rows);
    }

    private String formatSeconds(double seconds) {
        if (seconds < 1) {
            return text("safety.time.underSecond");
        }
        if (seconds < 90) {
            return text("safety.time.seconds", Math.round(seconds));
        }
        if (seconds < 5400) {
            return text("safety.time.minutes", Math.round(seconds / 60));
        }
        return text("safety.time.hours", String.format(Locale.ROOT, "%.1f", seconds / 3600));
    }

    private String text(String code, Object... args) {
        return messages.getMessage(code, args, code, LocaleContextHolder.getLocale());
    }
}
