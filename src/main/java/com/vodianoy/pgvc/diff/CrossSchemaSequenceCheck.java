package com.vodianoy.pgvc.diff;

import com.vodianoy.pgvc.model.schema.ColumnModel;
import com.vodianoy.pgvc.model.schema.DatabaseSnapshot;
import com.vodianoy.pgvc.model.schema.SequenceModel;
import com.vodianoy.pgvc.model.schema.TableModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.vodianoy.pgvc.util.SqlIdentifiers.ident;
import static com.vodianoy.pgvc.util.SqlIdentifiers.literal;
import static com.vodianoy.pgvc.util.SqlIdentifiers.qualified;

/**
 * v1.9.1: finds column defaults that take numbers from a sequence of another schema.
 *
 * <p>{@code CREATE TABLE mdc.mnemo_schemes (LIKE scada.mnemo_schemes INCLUDING ALL)} copies the default
 * {@code nextval('scada.mnemo_schemes_id_seq'::regclass)} as it is: the copy in {@code mdc} keeps using the sequence of
 * {@code scada}. Both tables then share one counter, and dropping {@code scada} (or its sequence) breaks every INSERT
 * into the copy. A default is reported when it points to a sequence of another schema and
 * <ul>
 *   <li>that sequence no longer exists, or</li>
 *   <li>the schema of the table has a sequence of the same name (the one the default was meant to use), or</li>
 *   <li>that sequence is OWNED BY the column of a same-named table of the other schema (the signature of LIKE).</li>
 * </ul>
 * A sequence shared on purpose (a global id generator in a schema of its own) matches none of these. The fix points the
 * default to the sequence of the table's own schema, creating it (OWNED BY the column, continuing after the largest
 * value) when it does not exist yet.
 */
public final class CrossSchemaSequenceCheck {

    public static final String KIND = "CROSS_SCHEMA_SEQUENCE";

    private static final Pattern NEXTVAL = Pattern.compile("nextval\\('((?:[^']|'')+)'(?:::regclass)?\\)",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> INTEGER_TYPES = Set.of("smallint", "integer", "bigint");

    /** One default that uses the sequence of another schema. */
    record Finding(TableModel table, ColumnModel column, String sourceSequence, String targetSequence, String reason,
                   QuickFix fix) {
    }

    private CrossSchemaSequenceCheck() {
    }

    /**
     * Adds a warning and a {@link QuickFix} to the change of every affected table (or column) of the diff; a finding
     * no change belongs to becomes a diff-level warning / quick fix. Texts in the language of the request.
     */
    public static void apply(SchemaDiffResult diff, DatabaseSnapshot live) {
        if (diff == null || live == null) {
            return;
        }
        for (Finding f : find(live, DiffTexts.current())) {
            String table = f.table().getQualifiedName();
            String column = table + "." + f.column().getName();
            ChangeItem target = diff.getChanges().stream()
                    .filter(c -> column.equals(c.getObjectName()))
                    .findFirst()
                    .or(() -> diff.getChanges().stream()
                            .filter(c -> table.equals(c.getObjectName()) || table.equals(c.getRootName()))
                            .findFirst())
                    .or(() -> diff.getChanges().stream()
                            .filter(c -> c.getObjectType() == ObjectType.SEQUENCE && f.sourceSequence().equals(c.getObjectName()))
                            .findFirst())
                    .orElse(null);
            if (target != null) {
                if (!target.getWarnings().contains(f.fix().message())) {
                    target.getWarnings().add(f.fix().message());
                }
                target.getQuickFixes().add(f.fix());
            } else {
                diff.getQuickFixes().add(f.fix());
            }
            if (!diff.getWarnings().contains(f.fix().message())) {
                diff.getWarnings().add(f.fix().message());
            }
        }
    }

    /** The quick fixes of a live schema (used to run a fix: the server never runs SQL the client sent). */
    public static List<QuickFix> quickFixes(DatabaseSnapshot live) {
        return find(live, DiffTexts.current()).stream().map(Finding::fix).toList();
    }

    static List<Finding> find(DatabaseSnapshot live, DiffTexts tx) {
        List<Finding> out = new ArrayList<>();
        List<TableModel> tables = new ArrayList<>(live.getTables().values());
        tables.sort(Comparator.comparing(TableModel::getQualifiedName));
        for (TableModel t : tables) {
            if (t.getParentTable() != null) {
                continue; // partitions inherit the default of the partitioned table, it is reported there
            }
            List<ColumnModel> columns = new ArrayList<>(t.getColumns().values());
            columns.sort(Comparator.comparingInt(ColumnModel::getAttnum));
            for (ColumnModel c : columns) {
                if (c.getDefaultValue() == null || c.isInherited()) {
                    continue;
                }
                Matcher m = NEXTVAL.matcher(c.getDefaultValue());
                if (!m.find()) {
                    continue;
                }
                List<String> name = parseName(m.group(1).replace("''", "'"));
                if (name.isEmpty()) {
                    continue;
                }
                String seqName = name.get(name.size() - 1);
                // an unqualified name resolves through search_path: version control reads with the default "public"
                String seqSchema = name.size() >= 2 ? name.get(name.size() - 2) : "public";
                if (seqSchema.equals(t.getSchemaName())) {
                    continue;
                }
                String sourceKey = DatabaseSnapshot.key(seqSchema, seqName);
                SequenceModel source = live.getSequences().get(sourceKey);
                if (name.size() < 2 && source == null) {
                    continue; // unqualified and not in public: it is in some other schema of search_path, unknown here
                }
                String ownKey = DatabaseSnapshot.key(t.getSchemaName(), seqName);
                SequenceModel own = live.getSequences().get(ownKey);
                String reason;
                if (source == null) {
                    reason = tx.t("seqDefault.missing", sourceKey, t.getQualifiedName());
                } else if (own != null) {
                    reason = tx.t("seqDefault.own", t.getSchemaName(), ownKey);
                } else if (source.getOwnedByTable() != null && !source.getOwnedByTable().equals(t.getQualifiedName())
                        && source.getOwnedByTable().endsWith("." + t.getName())) {
                    reason = tx.t("seqDefault.shared", sourceKey, source.getOwnedByTable());
                } else {
                    continue;
                }
                String message = tx.t("seqDefault.warning", c.getName(), t.getQualifiedName(), sourceKey) + " " + reason;
                String sql = fixSql(t, c, seqName, own, source);
                out.add(new Finding(t, c, sourceKey, ownKey, reason,
                        new QuickFix(KIND, t.getQualifiedName() + "." + c.getName(), message, sql)));
            }
        }
        return out;
    }

    /**
     * {@code ALTER TABLE a.t ALTER COLUMN id SET DEFAULT nextval('a.seq'::regclass);} - preceded, when schema a has no
     * such sequence yet, by its creation (type, increment and limits of the old one) continuing after the largest value.
     */
    static String fixSql(TableModel t, ColumnModel c, String seqName, SequenceModel own, SequenceModel source) {
        String table = qualified(t.getSchemaName(), t.getName());
        String column = ident(c.getName());
        String sequence = qualified(t.getSchemaName(), seqName);
        StringBuilder sql = new StringBuilder();
        boolean integer = INTEGER_TYPES.contains(c.getDataType() == null ? "" : c.getDataType().toLowerCase(Locale.ROOT));
        if (own == null) {
            sql.append("CREATE SEQUENCE IF NOT EXISTS ").append(sequence);
            if (source != null && source.getDataType() != null) {
                sql.append(" AS ").append(source.getDataType());
            } else if (integer) {
                sql.append(" AS ").append(c.getDataType().toLowerCase(Locale.ROOT));
            }
            if (source != null && source.getIncrementBy() != 0 && source.getIncrementBy() != 1) {
                sql.append(" INCREMENT BY ").append(source.getIncrementBy());
            }
            sql.append(";\n");
        }
        if (own == null || own.getOwnedByTable() == null) {
            sql.append("ALTER SEQUENCE ").append(sequence).append(" OWNED BY ").append(table).append('.').append(column)
                    .append(";\n");
        }
        if (integer) {
            // continue after the rows already in the table (they got numbers from the other schema's sequence)
            sql.append("SELECT pg_catalog.setval(").append(literal(sequence)).append("::regclass, COALESCE((SELECT max(")
                    .append(column).append(") FROM ").append(table).append("), 0) + 1, false);\n");
        }
        sql.append("ALTER TABLE ").append(table).append(" ALTER COLUMN ").append(column)
                .append(" SET DEFAULT nextval(").append(literal(sequence)).append("::regclass);");
        return sql.toString();
    }

    /** Parts of a (possibly quoted) dotted name: {@code "Sch"."seq"} -> [Sch, seq]; unquoted parts are lower-cased. */
    static List<String> parseName(String text) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean wasQuoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    current.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
                wasQuoted = true;
            } else if (ch == '.') {
                parts.add(wasQuoted ? current.toString() : current.toString().trim().toLowerCase(Locale.ROOT));
                current.setLength(0);
                wasQuoted = false;
            } else {
                current.append(ch);
            }
        }
        parts.add(wasQuoted ? current.toString() : current.toString().trim().toLowerCase(Locale.ROOT));
        parts.removeIf(String::isEmpty);
        return parts;
    }
}
