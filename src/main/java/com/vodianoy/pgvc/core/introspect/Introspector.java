package com.vodianoy.pgvc.core.introspect;

import com.vodianoy.pgvc.core.model.DatabaseSnapshot;
import com.vodianoy.pgvc.core.util.Json;
import com.vodianoy.pgvc.core.util.Resources;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Reads the current schema ("Working Tree") from pg_catalog. Works inside an open transaction, so it also
 * sees uncommitted DDL executed on the same connection (used by "Execute &amp; Commit").
 */
public final class Introspector {

    private static final String SQL = Resources.text("sql/introspect.sql");

    public DatabaseSnapshot snapshot(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(SQL)) {
            if (!rs.next()) {
                throw new SQLException("Introspection query returned no rows");
            }
            return Json.read(rs.getString(1), DatabaseSnapshot.class);
        }
    }
}
