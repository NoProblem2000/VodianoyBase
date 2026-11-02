package com.vodianoy.pgvc.vcs;

import java.util.List;

/**
 * Discard uncommitted changes of an environment.
 *
 * @param changeIndexes        null / empty: all changes (reset to HEAD), otherwise only these (positions in the
 *                             working-tree change list)
 * @param customDownSql        SQL to run instead of the generated one (edited in the UI)
 * @param expectedTotalChanges number of uncommitted changes the plan was made for; the request is refused when the
 *                             working tree changed meanwhile (null: not checked)
 * @param skipVerification     full reset: do not check that the schema equals HEAD afterwards
 * @param lockTimeout          blank: {@code pgvc.lock-timeout}
 * @param statementTimeout     blank: {@code pgvc.statement-timeout}
 */
public record DiscardRequest(String envId, List<Integer> changeIndexes, String customDownSql,
                             Integer expectedTotalChanges, boolean skipVerification, String lockTimeout,
                             String statementTimeout) {

    public DiscardRequest(String envId, List<Integer> changeIndexes) {
        this(envId, changeIndexes, null, null, false, null, null);
    }
}
