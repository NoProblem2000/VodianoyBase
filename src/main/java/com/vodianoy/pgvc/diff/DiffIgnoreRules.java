package com.vodianoy.pgvc.diff;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.vodianoy.pgvc.common.ApiException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * What the comparison of two schemas ignores. Applies everywhere the diff engine runs: the working tree, commits,
 * the checks before and after push / rollback, environment comparison, snapshot compare and the N-database matrix.
 *
 * <p>Object patterns are matched (case-insensitive) against the object name and against {@code schema.name}:
 * <ul>
 *   <li>glob (default): {@code %} or {@code *} = any characters, {@code ?} = one character, everything else literal
 *       ({@code _} too), e.g. {@code tmp_%}, {@code bak_*}, {@code audit.*_old};</li>
 *   <li>regular expression: {@code re:^tmp_\d+$} or {@code /^tmp_\d+$/} (searched within the name).</li>
 * </ul>
 * Patterns apply to tables, partitions, views, sequences, functions, ENUM types, triggers, indexes and extensions.
 *
 * @param ignoreComments         COMMENT ON TABLE / COLUMN / VIEW / FUNCTION / TYPE are not compared
 * @param ignoreTablespaces      table tablespaces are not compared (on by default: servers have different disks)
 * @param ignoreOwnersAndGrants  owners and GRANTs are not compared (on by default: roles differ between servers)
 * @param ignorePartitions       partitions are left out (each site may slice its partitioned tables differently);
 *                               the partitioned tables themselves are still compared
 * @param excludedObjectPatterns objects whose name matches are left out
 */
public record DiffIgnoreRules(boolean ignoreComments, boolean ignoreTablespaces, boolean ignoreOwnersAndGrants,
                              boolean ignorePartitions, List<String> excludedObjectPatterns) {

    /** Defaults of a new installation: owners, grants and tablespaces are ignored. */
    public static final DiffIgnoreRules DEFAULTS = new DiffIgnoreRules(false, true, true, false, List.of());

    /** Nothing is ignored. */
    public static final DiffIgnoreRules NONE = new DiffIgnoreRules(false, false, false, false, List.of());

    public DiffIgnoreRules {
        List<String> clean = new ArrayList<>();
        if (excludedObjectPatterns != null) {
            for (String p : excludedObjectPatterns) {
                if (p != null && !p.isBlank() && !clean.contains(p.strip())) {
                    clean.add(p.strip());
                }
            }
        }
        excludedObjectPatterns = List.copyOf(clean);
    }

    /** Throws 400 when a pattern is not a valid regular expression. */
    public DiffIgnoreRules validated() {
        for (String p : excludedObjectPatterns) {
            try {
                compile(p);
            } catch (PatternSyntaxException e) {
                throw ApiException.badRequest("ignore.invalidPattern", p, e.getDescription());
            }
        }
        return this;
    }

    /** True when an object called {@code name} in {@code schema} (null for database-wide objects) is excluded. */
    public boolean excludes(String schema, String name) {
        if (excludedObjectPatterns.isEmpty() || name == null) {
            return false;
        }
        String qualified = schema == null ? name : schema + "." + name;
        for (String p : excludedObjectPatterns) {
            Pattern pattern = compileQuietly(p);
            if (pattern == null) {
                continue;
            }
            if (isRegex(p)) {
                if (pattern.matcher(name).find() || pattern.matcher(qualified).find()) {
                    return true;
                }
            } else if (pattern.matcher(name).matches() || pattern.matcher(qualified).matches()) {
                return true;
            }
        }
        return false;
    }

    /** Nothing is ignored at all. */
    @JsonIgnore
    public boolean isEmpty() {
        return !ignoreComments && !ignoreTablespaces && !ignoreOwnersAndGrants && !ignorePartitions
                && excludedObjectPatterns.isEmpty();
    }

    // ------------------------------------------------------------------ patterns

    static boolean isRegex(String p) {
        return p.startsWith("re:") || (p.length() >= 2 && p.startsWith("/") && p.endsWith("/"));
    }

    static Pattern compile(String p) {
        if (p.startsWith("re:")) {
            return Pattern.compile(p.substring(3), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        }
        if (isRegex(p)) {
            return Pattern.compile(p.substring(1, p.length() - 1), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        }
        return Pattern.compile(globToRegex(p), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private static final Map<String, Optional<Pattern>> CACHE = new ConcurrentHashMap<>();

    private static Pattern compileQuietly(String p) {
        if (CACHE.size() > 1000) {
            CACHE.clear();
        }
        return CACHE.computeIfAbsent(p, k -> {
            try {
                return Optional.of(compile(k));
            } catch (PatternSyntaxException e) {
                return Optional.empty();
            }
        }).orElse(null);
    }

    /** {@code %} / {@code *} -> {@code .*}, {@code ?} -> {@code .}, everything else literal. */
    static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        for (char c : glob.toCharArray()) {
            if (c == '%' || c == '*' || c == '?') {
                if (!literal.isEmpty()) {
                    sb.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                sb.append(c == '?' ? "." : ".*");
            } else {
                literal.append(c);
            }
        }
        if (!literal.isEmpty()) {
            sb.append(Pattern.quote(literal.toString()));
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        List<String> parts = new ArrayList<>();
        if (ignoreComments) {
            parts.add("comments");
        }
        if (ignoreTablespaces) {
            parts.add("tablespaces");
        }
        if (ignoreOwnersAndGrants) {
            parts.add("owners & grants");
        }
        if (ignorePartitions) {
            parts.add("partitions");
        }
        if (!excludedObjectPatterns.isEmpty()) {
            parts.add("objects " + String.join(", ", excludedObjectPatterns));
        }
        return parts.isEmpty() ? "nothing ignored" : "ignored: " + String.join("; ", parts);
    }
}
