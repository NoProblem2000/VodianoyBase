package com.vodianoy.pgvc.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits PostgreSQL scripts into statements and steps without a full parser. Knows string literals ('...', E'...'),
 * quoted identifiers ("..."), dollar quoting ($$...$$, $tag$...$tag$), line and (nested) block comments, so a ';' or a
 * marker inside a function body is not taken for a boundary.
 *
 * <p>A line comment {@code -- pgvc:step} outside of any statement separates steps of a multi-step migration.
 */
public final class SqlScripts {

    /** Step separator: a line comment {@code -- pgvc:step} (anything after the marker is a free-text label). */
    public static final String STEP_MARKER = "-- pgvc:step";
    private static final Pattern MARKER = Pattern.compile("--\\s*pgvc:step\\b.*");
    private static final Pattern DOLLAR_TAG = Pattern.compile("\\$([A-Za-z_][A-Za-z0-9_]*)?\\$");

    private SqlScripts() {
    }

    /** Statements of the script (without the comments before them, ending with ';' when they had one). */
    public static List<String> statements(String script) {
        List<String> result = new ArrayList<>();
        for (List<String> step : steps(script)) {
            result.addAll(step);
        }
        return result;
    }

    /** Statements grouped by step; a script without markers is one step. Empty steps are dropped. */
    public static List<List<String>> steps(String script) {
        List<List<String>> steps = new ArrayList<>();
        List<String> current = new ArrayList<>();
        if (script == null) {
            return steps;
        }
        String s = script;
        int n = s.length();
        int start = 0;
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '-' && i + 1 < n && s.charAt(i + 1) == '-') {
                int end = s.indexOf('\n', i);
                end = end < 0 ? n : end;
                String comment = s.substring(i, end).strip();
                if (MARKER.matcher(comment).matches()) {
                    // statement text written before the marker (normally only whitespace or comments)
                    addStatement(current, s.substring(start, i));
                    if (!current.isEmpty()) {
                        steps.add(current);
                        current = new ArrayList<>();
                    }
                    start = end;
                }
                i = end;
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                i = skipBlockComment(s, i);
            } else if (c == '\'') {
                boolean escapes = i > 0 && (s.charAt(i - 1) == 'E' || s.charAt(i - 1) == 'e')
                        && (i < 2 || !isIdentifierChar(s.charAt(i - 2)));
                i = skipQuoted(s, i, '\'', escapes);
            } else if (c == '"') {
                i = skipQuoted(s, i, '"', false);
            } else if (c == '$' && (i == 0 || !isIdentifierChar(s.charAt(i - 1)))) {
                Matcher m = DOLLAR_TAG.matcher(s).region(i, n);
                if (m.lookingAt()) {
                    String tag = m.group();
                    int close = s.indexOf(tag, m.end());
                    i = close < 0 ? n : close + tag.length();
                } else {
                    i++;
                }
            } else if (c == ';') {
                addStatement(current, s.substring(start, i + 1));
                start = i + 1;
                i++;
            } else {
                i++;
            }
        }
        addStatement(current, s.substring(start));
        if (!current.isEmpty()) {
            steps.add(current);
        }
        return steps;
    }

    /** True when the script has a step marker outside of statements. */
    public static boolean hasStepMarkers(String script) {
        return steps(script).size() > 1;
    }

    /** The statement without comments, whitespace collapsed (for pattern matching, not for execution). */
    public static String normalize(String statement) {
        StringBuilder sb = new StringBuilder();
        String s = statement;
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '-' && i + 1 < n && s.charAt(i + 1) == '-') {
                int end = s.indexOf('\n', i);
                i = end < 0 ? n : end;
                space(sb);
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                i = skipBlockComment(s, i);
                space(sb);
            } else if (c == '\'' || c == '"') {
                int end = skipQuoted(s, i, c, false);
                sb.append(s, i, end);
                i = end;
            } else if (c == '$' && (i == 0 || !isIdentifierChar(s.charAt(i - 1)))) {
                Matcher m = DOLLAR_TAG.matcher(s).region(i, n);
                if (m.lookingAt()) {
                    int close = s.indexOf(m.group(), m.end());
                    int end = close < 0 ? n : close + m.group().length();
                    sb.append(s, i, end);
                    i = end;
                } else {
                    sb.append(c);
                    i++;
                }
            } else {
                if (Character.isWhitespace(c)) {
                    space(sb);
                } else {
                    sb.append(c);
                }
                i++;
            }
        }
        String result = sb.toString().strip();
        return result.endsWith(";") ? result.substring(0, result.length() - 1).strip() : result;
    }

    /** One space for any run of whitespace / comments outside of literals. */
    private static void space(StringBuilder sb) {
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ') {
            sb.append(' ');
        }
    }

    private static void addStatement(List<String> into, String text) {
        String t = stripLeadingComments(text).strip();
        if (!t.isEmpty() && !normalize(t).isEmpty()) {
            into.add(t);
        }
    }

    /** Comments written before a statement are not part of it (so patterns and replacements see the statement). */
    static String stripLeadingComments(String text) {
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && i + 1 < n && text.charAt(i + 1) == '-') {
                int end = text.indexOf('\n', i);
                i = end < 0 ? n : end + 1;
            } else if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                i = skipBlockComment(text, i);
            } else {
                break;
            }
        }
        return text.substring(i);
    }

    private static int skipBlockComment(String s, int i) {
        int depth = 0;
        int n = s.length();
        while (i < n) {
            if (s.startsWith("/*", i)) {
                depth++;
                i += 2;
            } else if (s.startsWith("*/", i)) {
                depth--;
                i += 2;
                if (depth == 0) {
                    return i;
                }
            } else {
                i++;
            }
        }
        return n;
    }

    /** Index after the closing quote; doubled quotes (and backslash escapes in E'') stay inside. */
    private static int skipQuoted(String s, int i, char quote, boolean backslashEscapes) {
        int n = s.length();
        int j = i + 1;
        while (j < n) {
            char c = s.charAt(j);
            if (backslashEscapes && c == '\\') {
                j += 2;
                continue;
            }
            if (c == quote) {
                if (j + 1 < n && s.charAt(j + 1) == quote) {
                    j += 2;
                    continue;
                }
                return j + 1;
            }
            j++;
        }
        return n;
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }
}
