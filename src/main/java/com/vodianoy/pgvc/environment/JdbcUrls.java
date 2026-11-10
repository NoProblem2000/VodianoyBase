package com.vodianoy.pgvc.environment;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds and parses PostgreSQL JDBC URLs {@code jdbc:postgresql://host[:port][,host2[:port2]...]/database[?params]}.
 * Only the first host is taken apart (the environment editor shows host / port / database fields); the original URL of
 * a multi-host connection is kept as it is.
 */
public final class JdbcUrls {

    public static final String PREFIX = "jdbc:postgresql:";

    private static final Pattern URL = Pattern.compile(
            "^jdbc:postgresql://(?<hosts>[^/?]*)(?:/(?<db>[^?]*))?(?:\\?(?<params>.*))?$");
    private static final Pattern HOST_PORT = Pattern.compile("^(?:\\[(?<v6>[^\\]]+)]|(?<host>[^:]*))(?::(?<port>\\d+))?$");

    /** Parts of a JDBC URL; {@code params} is the query string without '?', null when there is none. */
    public record Parts(String host, Integer port, String database, String params, boolean multiHost) {
    }

    private JdbcUrls() {
    }

    /** {@code jdbc:postgresql://host:port/database[?params]}; IPv6 addresses are put into brackets. */
    public static String build(String host, Integer port, String database, String params) {
        String h = host.trim();
        if (h.contains(":") && !h.startsWith("[")) {
            h = "[" + h + "]";
        }
        StringBuilder url = new StringBuilder("jdbc:postgresql://").append(h).append(':')
                .append(port == null ? Environment.DEFAULT_PORT : port).append('/')
                .append(URLEncoder.encode(database == null ? "" : database.trim(), StandardCharsets.UTF_8).replace("+", "%20"));
        if (params != null && !params.isBlank()) {
            url.append('?').append(params);
        }
        return url.toString();
    }

    /** Host, port, database and parameters of a JDBC URL, null when it is not a {@code jdbc:postgresql://} URL. */
    public static Parts parse(String jdbcUrl) {
        if (jdbcUrl == null) {
            return null;
        }
        Matcher m = URL.matcher(jdbcUrl.trim());
        if (!m.matches()) {
            return null;
        }
        String hosts = m.group("hosts");
        boolean multi = hosts.contains(",");
        String first = multi ? hosts.substring(0, hosts.indexOf(',')) : hosts;
        Matcher hp = HOST_PORT.matcher(first);
        String host = null;
        Integer port = null;
        if (hp.matches()) {
            host = hp.group("v6") != null ? hp.group("v6") : hp.group("host");
            port = hp.group("port") == null ? null : Integer.valueOf(hp.group("port"));
        }
        if (host == null || host.isEmpty()) {
            host = "localhost";
        }
        String db = m.group("db");
        db = db == null || db.isEmpty() ? null : URLDecoder.decode(db, StandardCharsets.UTF_8);
        return new Parts(host, port == null ? Environment.DEFAULT_PORT : port, db, m.group("params"), multi);
    }

    /** The same URL pointing to another database of the server (used to list the databases through "postgres"). */
    public static String withDatabase(String jdbcUrl, String database) {
        Parts p = parse(jdbcUrl);
        if (p == null) {
            return jdbcUrl;
        }
        Matcher m = URL.matcher(jdbcUrl.trim());
        if (!m.matches()) {
            return jdbcUrl;
        }
        StringBuilder url = new StringBuilder("jdbc:postgresql://").append(m.group("hosts")).append('/')
                .append(URLEncoder.encode(database, StandardCharsets.UTF_8).replace("+", "%20"));
        if (p.params() != null && !p.params().isBlank()) {
            url.append('?').append(p.params());
        }
        return url.toString();
    }
}
