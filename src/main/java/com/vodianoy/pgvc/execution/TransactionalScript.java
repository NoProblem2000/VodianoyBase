package com.vodianoy.pgvc.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A script made executable inside one transaction (dry-run, offline bundle, atomic push, rollback):
 * {@code CONCURRENTLY} is removed from index / detach statements, {@code VACUUM} is skipped, {@code ALTER TYPE ...
 * ADD VALUE} stays (PostgreSQL 12+ accepts it in a transaction; PostgreSQL 11 refuses it); step markers stay as
 * comments.
 *
 * @param rewritten statements that were changed, as they were written
 */
public record TransactionalScript(String sql, List<String> rewritten) {

    private static final Pattern CONCURRENTLY = Pattern.compile("(?i)\\s+CONCURRENTLY\\b");
    private static final Pattern VACUUM = Pattern.compile("(?is)^VACUUM\\b.*");

    public boolean changed() {
        return !rewritten.isEmpty();
    }

    public static TransactionalScript of(String script) {
        if (script == null || script.isBlank()) {
            return new TransactionalScript(script == null ? "" : script, List.of());
        }
        List<List<String>> steps = SqlScripts.steps(script);
        boolean anything = steps.stream().flatMap(List::stream).anyMatch(MigrationStep::isNonTransactional);
        if (!anything) {
            return new TransactionalScript(script, List.of());
        }
        List<String> rewritten = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
            if (i > 0) {
                sb.append(SqlScripts.STEP_MARKER).append('\n');
            }
            for (String statement : steps.get(i)) {
                if (!MigrationStep.isNonTransactional(statement)) {
                    sb.append(statement).append('\n');
                } else if (MigrationStep.isEnumAddValue(statement)) {
                    sb.append("-- pgvc: ADD VALUE inside the transaction (PostgreSQL 12+; the new label is usable after "
                            + "COMMIT)\n").append(statement).append('\n');
                } else if (VACUUM.matcher(SqlScripts.normalize(statement)).matches()) {
                    rewritten.add(statement);
                    sb.append("-- pgvc: skipped (VACUUM cannot run inside a transaction): ")
                            .append(SqlScripts.normalize(statement)).append('\n');
                } else {
                    rewritten.add(statement);
                    sb.append("-- pgvc: CONCURRENTLY removed (runs inside one transaction)\n")
                            .append(CONCURRENTLY.matcher(statement).replaceFirst("")).append('\n');
                }
            }
        }
        return new TransactionalScript(sb.toString(), List.copyOf(rewritten));
    }
}
