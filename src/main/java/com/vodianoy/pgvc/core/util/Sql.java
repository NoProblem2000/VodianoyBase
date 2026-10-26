package com.vodianoy.pgvc.core.util;

import java.util.Set;
import java.util.regex.Pattern;

/** SQL text helpers: identifier quoting, literals, dollar quoting. */
public final class Sql {

    private static final Pattern SIMPLE_IDENT = Pattern.compile("[a-z_][a-z0-9_$]*");

    /** PostgreSQL keywords that are not "unreserved" (pg_get_keywords() catcode <> 'U'). */
    private static final Set<String> KEYWORDS = Set.of(
            "all", "analyse", "analyze", "and", "any", "array", "as", "asc", "asymmetric", "authorization",
            "between", "bigint", "binary", "bit", "boolean", "both", "case", "cast", "char", "character", "check",
            "coalesce", "collate", "collation", "column", "concurrently", "constraint", "create", "cross",
            "current_catalog", "current_date", "current_role", "current_schema", "current_time", "current_timestamp",
            "current_user", "dec", "decimal", "default", "deferrable", "desc", "distinct", "do", "else", "end",
            "except", "exists", "extract", "false", "fetch", "float", "for", "foreign", "freeze", "from", "full",
            "grant", "greatest", "group", "grouping", "having", "ilike", "in", "initially", "inner", "inout", "int",
            "integer", "intersect", "interval", "into", "is", "isnull", "join", "json", "json_array", "json_arrayagg",
            "json_exists", "json_object", "json_objectagg", "json_query", "json_scalar", "json_serialize",
            "json_table", "json_value", "lateral", "leading", "least", "left", "like", "limit", "localtime",
            "localtimestamp", "merge_action", "national", "natural", "nchar", "none", "normalize", "not", "notnull",
            "null", "nullif", "numeric", "offset", "on", "only", "or", "order", "out", "outer", "overlaps", "overlay",
            "placing", "position", "precision", "primary", "real", "references", "returning", "right", "row",
            "select", "session_user", "setof", "similar", "smallint", "some", "substring", "symmetric", "system_user",
            "table", "tablesample", "then", "time", "timestamp", "to", "trailing", "treat", "trim", "true", "union",
            "unique", "user", "using", "values", "varchar", "variadic", "verbose", "when", "where", "window", "with",
            "xmlattributes", "xmlconcat", "xmlelement", "xmlexists", "xmlforest", "xmlnamespaces", "xmlparse",
            "xmlpi", "xmlroot", "xmlserialize", "xmltable");

    private Sql() {
    }

    /** Quotes an identifier the same way quote_ident() does. */
    public static String ident(String name) {
        if (name == null) {
            throw new IllegalArgumentException("identifier is null");
        }
        if (SIMPLE_IDENT.matcher(name).matches() && !KEYWORDS.contains(name)) {
            return name;
        }
        return '"' + name.replace("\"", "\"\"") + '"';
    }

    /** Schema-qualified, quoted name. */
    public static String qn(String schema, String name) {
        return ident(schema) + "." + ident(name);
    }

    /** Standard SQL string literal (standard_conforming_strings = on). */
    public static String literal(String value) {
        if (value == null) {
            return "NULL";
        }
        return "'" + value.replace("'", "''") + "'";
    }

    /** Dollar-quoted literal with a tag guaranteed not to occur inside the value. */
    public static String dollar(String value) {
        if (value == null) {
            return "NULL";
        }
        String tag = "$pgvc$";
        int n = 0;
        while (value.contains(tag)) {
            n++;
            tag = "$pgvc" + n + "$";
        }
        return tag + value + tag;
    }
}
