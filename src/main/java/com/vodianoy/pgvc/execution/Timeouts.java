package com.vodianoy.pgvc.execution;

import com.vodianoy.pgvc.common.ApiException;

import java.util.regex.Pattern;

/**
 * {@code lock_timeout} and {@code statement_timeout} of a migration, e.g. {@code 3s} / {@code 60s} ({@code 0} = no
 * limit). Values are validated before they are written into {@code SET LOCAL}.
 */
public record Timeouts(String lockTimeout, String statementTimeout) {

    public static final String DEFAULT_LOCK_TIMEOUT = "3s";
    public static final String DEFAULT_STATEMENT_TIMEOUT = "60s";

    private static final Pattern DURATION = Pattern.compile("(?i)\\d+(\\.\\d+)?\\s*(us|ms|s|min|h|d)?");

    public Timeouts {
        lockTimeout = validate("lock_timeout", lockTimeout, DEFAULT_LOCK_TIMEOUT);
        statementTimeout = validate("statement_timeout", statementTimeout, DEFAULT_STATEMENT_TIMEOUT);
    }

    /** Requested values, blank ones replaced by the given defaults. */
    public static Timeouts of(String lockTimeout, String statementTimeout, Timeouts defaults) {
        return new Timeouts(blank(lockTimeout) ? defaults.lockTimeout() : lockTimeout,
                blank(statementTimeout) ? defaults.statementTimeout() : statementTimeout);
    }

    /** {@code SET LOCAL ...} for the current transaction. */
    public String setLocalSql() {
        return "SET LOCAL lock_timeout = '" + lockTimeout + "'; SET LOCAL statement_timeout = '" + statementTimeout + "'";
    }

    /** Session-level variant for statements that run outside a transaction (reset afterwards). */
    public String setSessionSql() {
        return "SET lock_timeout = '" + lockTimeout + "'; SET statement_timeout = '" + statementTimeout + "'";
    }

    public static String resetSessionSql() {
        return "RESET lock_timeout; RESET statement_timeout";
    }

    private static String validate(String name, String value, String fallback) {
        String v = blank(value) ? fallback : value.strip();
        if (!DURATION.matcher(v).matches()) {
            throw ApiException.badRequest("migration.badTimeout", name, v);
        }
        return v.replaceAll("\\s+", "");
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
