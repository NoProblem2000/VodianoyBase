package com.vodianoy.pgvc.environment;

import com.zaxxer.hikari.SQLExceptionOverride;

import java.sql.SQLException;

/**
 * HikariCP treats SQLSTATE 0A000 ("feature not supported") as a broken connection and closes it. PostgreSQL uses that
 * state for ordinary statement errors, e.g. "cannot alter type of a column used by a view or rule" - exactly the
 * errors a migration tool must report. Closing the connection in the middle of the transaction would make the rollback
 * fail and hide the real message, so such errors keep the connection.
 */
public class PgErrorExceptionOverride implements SQLExceptionOverride {

    @java.lang.Override
    public Override adjudicate(SQLException e) {
        return "0A000".equals(e.getSQLState()) ? Override.DO_NOT_EVICT : Override.CONTINUE_EVICT;
    }
}
