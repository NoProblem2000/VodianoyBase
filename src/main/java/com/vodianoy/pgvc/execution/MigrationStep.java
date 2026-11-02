package com.vodianoy.pgvc.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * One step of a migration. A transactional step runs as one transaction; a non-transactional one (it contains
 * {@code CREATE / DROP INDEX CONCURRENTLY}, {@code REINDEX ... CONCURRENTLY}, {@code DETACH PARTITION ... CONCURRENTLY}
 * {@code VACUUM} or {@code ALTER TYPE ... ADD VALUE}) runs statement by statement outside of any transaction.
 *
 * @param index 1-based
 */
public record MigrationStep(int index, List<String> statements, boolean transactional) {

    private static final Pattern NON_TRANSACTIONAL = Pattern.compile(
            "(?is)^(CREATE\\s+(UNIQUE\\s+)?INDEX\\s+CONCURRENTLY\\b.*|DROP\\s+INDEX\\s+CONCURRENTLY\\b.*"
                    + "|REINDEX\\b.*\\bCONCURRENTLY\\b.*|ALTER\\s+TABLE\\b.*\\bDETACH\\s+PARTITION\\b.*\\bCONCURRENTLY\\b.*"
                    + "|VACUUM\\b.*|ALTER\\s+TYPE\\b.*\\bADD\\s+VALUE\\b.*)");

    public String sql() {
        return String.join("\n", statements);
    }

    private static final Pattern ENUM_ADD_VALUE = Pattern.compile("(?is)^ALTER\\s+TYPE\\b.*\\bADD\\s+VALUE\\b.*");

    /** {@code ALTER TYPE ... ADD VALUE}: a transaction block is refused on PostgreSQL 11 only. */
    public static boolean isEnumAddValue(String statement) {
        return ENUM_ADD_VALUE.matcher(SqlScripts.normalize(statement)).matches();
    }

    public static boolean isNonTransactional(String statement) {
        return NON_TRANSACTIONAL.matcher(SqlScripts.normalize(statement)).matches();
    }

    /** Steps of a script: split at {@code -- pgvc:step} markers. */
    public static List<MigrationStep> plan(String script) {
        List<MigrationStep> steps = new ArrayList<>();
        for (List<String> statements : SqlScripts.steps(script)) {
            boolean transactional = statements.stream().noneMatch(MigrationStep::isNonTransactional);
            steps.add(new MigrationStep(steps.size() + 1, List.copyOf(statements), transactional));
        }
        return steps;
    }

    /** True when the script needs more than one transaction (markers or a non-transactional statement). */
    public static boolean isMultiStep(List<MigrationStep> steps) {
        return steps.size() > 1 || steps.stream().anyMatch(s -> !s.transactional());
    }
}
