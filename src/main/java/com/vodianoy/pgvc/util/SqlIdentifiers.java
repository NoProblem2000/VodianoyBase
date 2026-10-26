package com.vodianoy.pgvc.util;

import java.util.Set;
import java.util.regex.Pattern;

/** Identifier quoting and literals for generated SQL (behaves like PostgreSQL quote_ident / quote_literal). */
public final class SqlIdentifiers {

    private static final Pattern SIMPLE = Pattern.compile("[a-z_][a-z0-9_$]*");

    /** Keywords that are not "unreserved" in PostgreSQL (pg_get_keywords() catcode <> 'U'). */
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

    private SqlIdentifiers() {
    }

    /** Quotes an identifier only when needed: {@code orders}, {@code "Mixed Case"}, {@code "order"}. */
    public static String ident(String name) {
        if (name == null) {
            throw new IllegalArgumentException("identifier is null");
        }
        if (SIMPLE.matcher(name).matches() && !KEYWORDS.contains(name)) {
            return name;
        }
        return '"' + name.replace("\"", "\"\"") + '"';
    }

    /** {@code schema.name}, both parts quoted when needed. */
    public static String qualified(String schema, String name) {
        return ident(schema) + "." + ident(name);
    }

    /** Quotes a model key {@code schema.name} (the schema never contains a dot in version-controlled schemas). */
    public static String qualifiedKey(String key) {
        int dot = key.indexOf('.');
        return dot < 0 ? ident(key) : qualified(key.substring(0, dot), key.substring(dot + 1));
    }

    /** SQL string literal (standard_conforming_strings = on). */
    public static String literal(String value) {
        return value == null ? "NULL" : "'" + value.replace("'", "''") + "'";
    }
}
