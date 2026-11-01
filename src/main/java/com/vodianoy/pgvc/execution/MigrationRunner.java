package com.vodianoy.pgvc.execution;

import com.vodianoy.pgvc.common.ApiException;
import com.vodianoy.pgvc.config.PgVcProperties;
import com.vodianoy.pgvc.environment.EnvironmentManagerService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.sql.Statement;

/**
 * Runs migration steps with {@code lock_timeout} / {@code statement_timeout}: a transactional step in its own
 * transaction ({@code SET LOCAL}), a non-transactional one statement by statement in autocommit mode (session-level
 * {@code SET}, reset afterwards, so the pooled connection is left clean).
 */
@Component
@RequiredArgsConstructor
public class MigrationRunner {

    private final EnvironmentManagerService environments;
    private final PgVcProperties properties;
    private final org.springframework.context.MessageSource messages;

    /** The configured defaults ({@code pgvc.lock-timeout}, {@code pgvc.statement-timeout}). */
    public Timeouts defaults() {
        return new Timeouts(properties.getLockTimeout(), properties.getStatementTimeout());
    }

    public Timeouts timeouts(String lockTimeout, String statementTimeout) {
        return Timeouts.of(lockTimeout, statementTimeout, defaults());
    }

    /** {@code SET LOCAL} the timeouts in the current transaction of the environment. */
    public void applyLocal(String envId, Timeouts timeouts) {
        environments.getJdbcTemplate(envId).execute(timeouts.setLocalSql());
    }

    /** Executes SQL in the current transaction; lock / statement timeouts become readable errors. */
    public long execute(String envId, String sql, Timeouts timeouts) {
        long t0 = System.nanoTime();
        try {
            environments.getJdbcTemplate(envId).execute(sql);
        } catch (DataAccessException e) {
            throw translate(e, timeouts).orElseThrow(() -> e);
        }
        return (System.nanoTime() - t0) / 1_000_000;
    }

    /** Runs a step: in its own transaction, or statement by statement outside of a transaction. */
    public StepReport run(String envId, MigrationStep step, Timeouts timeouts) {
        long t0 = System.nanoTime();
        if (step.transactional()) {
            environments.getTransactionTemplate(envId).executeWithoutResult(tx -> {
                applyLocal(envId, timeouts);
                execute(envId, step.sql(), timeouts);
            });
        } else {
            JdbcTemplate jdbc = environments.getJdbcTemplate(envId);
            try {
                jdbc.execute((java.sql.Connection connection) -> {
                    boolean autoCommit = connection.getAutoCommit();
                    connection.setAutoCommit(true);
                    try (Statement st = connection.createStatement()) {
                        st.execute(timeouts.setSessionSql());
                        try {
                            for (String statement : step.statements()) {
                                st.execute(statement);
                            }
                        } finally {
                            st.execute(Timeouts.resetSessionSql());
                        }
                    } finally {
                        connection.setAutoCommit(autoCommit);
                    }
                    return null;
                });
            } catch (DataAccessException e) {
                throw translate(e, timeouts).orElseThrow(() -> e);
            }
        }
        return new StepReport(step.index(), step.transactional(), step.statements().size(),
                (System.nanoTime() - t0) / 1_000_000);
    }

    /** lock_timeout (55P03) and statement_timeout (57014) as readable, localized errors. */
    public static java.util.Optional<ApiException> translate(Throwable e, Timeouts timeouts) {
        Throwable root = NestedExceptionUtils.getMostSpecificCause(e);
        String state = root instanceof SQLException sql ? sql.getSQLState() : null;
        if ("55P03".equals(state)) {
            return java.util.Optional.of(new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "migration.lockTimeout",
                    timeouts.lockTimeout(), root.getMessage()));
        }
        if ("57014".equals(state)) {
            return java.util.Optional.of(new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "migration.statementTimeout",
                    timeouts.statementTimeout(), root.getMessage()));
        }
        return java.util.Optional.empty();
    }

    /**
     * The error of a failed step as a message argument: an {@link ApiException} stays translatable (rendered in the
     * language of the request), anything else becomes its root cause message (PostgreSQL's own text).
     */
    public static Object messageArgument(Throwable e) {
        if (e instanceof ApiException api) {
            return new org.springframework.context.support.DefaultMessageSourceResolvable(
                    new String[]{api.getCode()}, api.getArgs(), api.getMessage());
        }
        Throwable root = NestedExceptionUtils.getMostSpecificCause(e);
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    /** The error in the language of the current request (for reports such as a dry-run). */
    public String describe(Throwable e) {
        Object arg = messageArgument(e);
        if (arg instanceof org.springframework.context.MessageSourceResolvable r) {
            return messages.getMessage(r, org.springframework.context.i18n.LocaleContextHolder.getLocale());
        }
        return String.valueOf(arg);
    }
}
