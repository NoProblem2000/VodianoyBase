package com.vodianoy.pgvc.core.env;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/** Opens plain JDBC connections to an environment (no pooling - this is a developer desktop tool). */
public class ConnectionFactory {

    private final int connectTimeoutSeconds;

    public ConnectionFactory(int connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    public Connection open(EnvironmentConfig env) throws SQLException {
        Properties props = new Properties();
        if (env.username() != null && !env.username().isEmpty()) {
            props.setProperty("user", env.username());
        }
        if (env.password() != null && !env.password().isEmpty()) {
            props.setProperty("password", env.password());
        }
        props.setProperty("connectTimeout", Integer.toString(connectTimeoutSeconds));
        props.setProperty("loginTimeout", Integer.toString(connectTimeoutSeconds));
        props.setProperty("ApplicationName", "pg-git-vc");
        Connection c = DriverManager.getConnection(env.jdbcUrl(), props);
        c.setAutoCommit(true);
        return c;
    }
}
