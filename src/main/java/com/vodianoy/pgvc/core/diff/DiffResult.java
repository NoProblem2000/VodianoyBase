package com.vodianoy.pgvc.core.diff;

import java.util.List;

/**
 * Result of comparing two snapshots.
 *
 * @param statements ordered DDL statements that transform "from" into "to"
 * @param warnings   potentially dangerous or manual-attention items
 * @param summary    human readable list of changes ("+ table public.users", ...)
 */
public record DiffResult(List<String> statements, List<String> warnings, List<String> summary) {

    public DiffResult {
        statements = List.copyOf(statements);
        warnings = List.copyOf(warnings);
        summary = List.copyOf(summary);
    }

    public boolean hasChanges() {
        return !statements.isEmpty();
    }

    /** Executable SQL script: warnings as comments followed by the statements. */
    public String sql() {
        StringBuilder sb = new StringBuilder();
        for (String w : warnings) {
            sb.append("-- WARNING: ").append(w.replace("\n", " ")).append('\n');
        }
        if (!warnings.isEmpty()) {
            sb.append('\n');
        }
        if (statements.isEmpty()) {
            sb.append("-- No schema changes\n");
        } else {
            for (String s : statements) {
                sb.append(s).append('\n');
            }
        }
        return sb.toString();
    }
}
