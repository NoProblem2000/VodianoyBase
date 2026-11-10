package com.vodianoy.pgvc.environment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vodianoy.pgvc.common.ApiException;
import com.vodianoy.pgvc.config.PgVcProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Keeps the list of environments in pgvc-environments.json, remembers the active one and hands out a cached
 * {@link JdbcTemplate} / {@link TransactionTemplate} (backed by a small connection pool) per environment.
 */
@Slf4j
@Service
public class EnvironmentManagerService implements DisposableBean {

    static final int MIN_SERVER_VERSION = 110000;

    private static final Set<String> RESERVED_IDS = Set.of("active", "test");
    private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");

    /** Maintenance database used to list the databases of a server (falls back to template1). */
    static final String MAINTENANCE_DATABASE = "postgres";

    // no pg_database_size(): it walks every file of every database, which takes seconds on big production servers
    static final String DATABASES_SQL = "SELECT d.datname, pg_catalog.shobj_description(d.oid, 'pg_database') AS comment,"
            + " pg_catalog.has_database_privilege(d.oid, 'CONNECT') AS can_connect"
            + " FROM pg_catalog.pg_database d WHERE d.datistemplate = false ORDER BY d.datname";

    static final String SCHEMAS_SQL = "SELECT n.nspname, pg_catalog.obj_description(n.oid, 'pg_namespace') AS comment,"
            + " pg_catalog.pg_get_userbyid(n.nspowner) AS owner,"
            + " (SELECT count(*) FROM pg_catalog.pg_class c WHERE c.relnamespace = n.oid"
            + "   AND c.relkind IN ('r', 'p', 'v', 'm', 'S', 'f')) AS table_count,"
            + " EXISTS (SELECT 1 FROM pg_catalog.pg_depend d WHERE d.classid = 'pg_catalog.pg_namespace'::regclass"
            + "   AND d.objid = n.oid AND d.deptype = 'e') AS extension"
            + " FROM pg_catalog.pg_namespace n"
            + " WHERE n.nspname NOT IN ('pg_catalog', 'information_schema', '_pgvc')"
            + " AND n.nspname NOT LIKE 'pg\\_toast%' AND n.nspname NOT LIKE 'pg\\_temp\\_%'"
            + " ORDER BY n.nspname";
    private static final String[] PALETTE = {"#22c55e", "#3b82f6", "#f59e0b", "#ef4444", "#a855f7", "#14b8a6", "#ec4899"};

    private final PgVcProperties properties;
    private final ObjectMapper objectMapper;
    private final Path file;
    private final Object lock = new Object();
    private final Map<String, EnvironmentConnection> connections = new ConcurrentHashMap<>();

    /** Guarded by {@link #lock}. */
    private EnvironmentsFile state;

    /** Pool + templates of one environment. */
    record EnvironmentConnection(HikariDataSource dataSource, JdbcTemplate jdbcTemplate,
                                 TransactionTemplate transactionTemplate) {
    }

    public EnvironmentManagerService(PgVcProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.file = Path.of(properties.getEnvironmentsFile()).toAbsolutePath().normalize();
        this.state = load();
        log.info("Environments file: {} ({} environment(s))", file, state.getEnvironments().size());
    }

    public Path getFile() {
        return file;
    }

    // ------------------------------------------------------------------ queries

    public List<Environment> listEnvironments() {
        synchronized (lock) {
            return state.getEnvironments().stream().map(e -> e.toBuilder().build()).toList();
        }
    }

    public List<EnvironmentView> listViews() {
        synchronized (lock) {
            return state.getEnvironments().stream()
                    .map(e -> EnvironmentView.of(e, e.getId().equals(state.getActiveEnvironmentId())))
                    .toList();
        }
    }

    public Environment getEnvironment(String id) {
        synchronized (lock) {
            return find(id).toBuilder().build();
        }
    }

    public EnvironmentView getView(String id) {
        synchronized (lock) {
            return EnvironmentView.of(find(id), id.equals(state.getActiveEnvironmentId()));
        }
    }

    public Optional<Environment> getActiveEnvironment() {
        synchronized (lock) {
            String id = state.getActiveEnvironmentId();
            return id == null ? Optional.empty() : Optional.of(find(id).toBuilder().build());
        }
    }

    // ------------------------------------------------------------------ changes

    public Environment addEnvironment(Environment input) {
        synchronized (lock) {
            Environment env = normalize(input, null);
            env.setId(uniqueId(env.getName()));
            state.getEnvironments().add(env);
            if (state.getActiveEnvironmentId() == null) {
                state.setActiveEnvironmentId(env.getId());
            }
            persist();
            log.info("Environment added: {} ({})", env.getName(), env.getId());
            return env.toBuilder().build();
        }
    }

    /** Updates an environment. A null password keeps the stored one. Cached connections are recreated. */
    public Environment updateEnvironment(String id, Environment input) {
        synchronized (lock) {
            Environment existing = find(id);
            Environment env = normalize(input, existing);
            env.setId(id);
            state.getEnvironments().set(state.getEnvironments().indexOf(existing), env);
            persist();
            closeConnection(id);
            return env.toBuilder().build();
        }
    }

    public void deleteEnvironment(String id) {
        synchronized (lock) {
            Environment existing = find(id);
            state.getEnvironments().remove(existing);
            if (id.equals(state.getActiveEnvironmentId())) {
                state.setActiveEnvironmentId(state.getEnvironments().isEmpty() ? null : state.getEnvironments().get(0).getId());
            }
            persist();
            closeConnection(id);
        }
    }

    public Environment activateEnvironment(String id) {
        synchronized (lock) {
            Environment env = find(id);
            state.setActiveEnvironmentId(id);
            persist();
            return env.toBuilder().build();
        }
    }

    // ------------------------------------------------------------------ connections

    public JdbcTemplate getJdbcTemplate(String envId) {
        return connection(envId).jdbcTemplate();
    }

    public TransactionTemplate getTransactionTemplate(String envId) {
        return connection(envId).transactionTemplate();
    }

    private EnvironmentConnection connection(String envId) {
        Environment env = getEnvironment(envId);
        return connections.computeIfAbsent(envId, id -> createConnection(env));
    }

    private EnvironmentConnection createConnection(Environment env) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("pgvc-" + env.getId());
        config.setJdbcUrl(env.getJdbcUrl());
        config.setUsername(env.getUsername());
        config.setPassword(env.getPassword());
        config.setMaximumPoolSize(properties.getMaxPoolSize());
        config.setMinimumIdle(0);
        config.setIdleTimeout(60_000);
        config.setConnectionTimeout(Math.max(250L, properties.getConnectTimeoutSeconds() * 1000L));
        // do not fail (or block) when the database is down at pool creation; errors surface on first use
        config.setInitializationFailTimeout(-1);
        config.setDataSourceProperties(driverProperties(env));
        // keep the connection on statement errors with SQLSTATE 0A000 (see PgErrorExceptionOverride)
        config.setExceptionOverrideClassName(PgErrorExceptionOverride.class.getName());
        HikariDataSource dataSource = new HikariDataSource(config);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        return new EnvironmentConnection(dataSource, new JdbcTemplate(dataSource), tx);
    }

    private Properties driverProperties(Environment env) {
        Properties p = new Properties();
        p.setProperty("connectTimeout", Integer.toString(properties.getConnectTimeoutSeconds()));
        p.setProperty("loginTimeout", Integer.toString(properties.getConnectTimeoutSeconds()));
        p.setProperty("ApplicationName", "pg-git-vc");
        p.setProperty("currentSchema", env.getSchemaName());
        return p;
    }

    private void closeConnection(String envId) {
        EnvironmentConnection c = connections.remove(envId);
        if (c != null) {
            c.dataSource().close();
        }
    }

    @Override
    public void destroy() {
        connections.keySet().forEach(this::closeConnection);
    }

    // ------------------------------------------------------------------ connection test

    /** Tests a stored environment. */
    public ConnectionTestResult testConnection(String envId) {
        return testConnection(getEnvironment(envId));
    }

    /**
     * Tests connection settings that may not be saved yet ({@code SELECT version()}). When the candidate has the id of
     * a stored environment and no password, the stored password is used.
     */
    public ConnectionTestResult testConnection(Environment candidate) {
        if (candidate == null) {
            throw ApiException.badRequest("env.required");
        }
        Environment env = candidate.toBuilder().build();
        env.setJdbcUrl(connectionUrl(env));
        if (env.getPassword() == null && env.getId() != null) {
            synchronized (lock) {
                state.getEnvironments().stream().filter(e -> e.getId().equals(env.getId())).findFirst()
                        .ifPresent(stored -> env.setPassword(stored.getPassword()));
            }
        }
        if (env.getSchemaName() == null || env.getSchemaName().isBlank()) {
            env.setSchemaName(Environment.DEFAULT_SCHEMA);
        }
        Properties props = driverProperties(env);
        if (env.getUsername() != null && !env.getUsername().isEmpty()) {
            props.setProperty("user", env.getUsername());
        }
        if (env.getPassword() != null && !env.getPassword().isEmpty()) {
            props.setProperty("password", env.getPassword());
        }
        try (Connection c = DriverManager.getConnection(env.getJdbcUrl(), props);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT version(), current_setting('server_version_num')::int,"
                     + " (SELECT rolsuper FROM pg_catalog.pg_roles WHERE rolname = current_user)")) {
            rs.next();
            String version = rs.getString(1);
            int number = rs.getInt(2);
            boolean superuser = rs.getBoolean(3);
            if (number < MIN_SERVER_VERSION) {
                return new ConnectionTestResult(false, version, number, superuser, "PostgreSQL 11 or newer is required");
            }
            return new ConnectionTestResult(true, version, number, superuser, superuser
                    ? "Connected"
                    : "Connected, but the user is not a SUPERUSER: version control cannot be initialized with it");
        } catch (SQLException e) {
            return new ConnectionTestResult(false, null, null, false, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ database / schema browser

    /**
     * Databases of the server (connects to the maintenance database {@code postgres}, then {@code template1}, then the
     * database of the request).
     */
    public List<DiscoveredDatabase> discoverDatabases(DiscoveryRequest request) {
        Environment env = discoveryEnvironment(request);
        LinkedHashSet<String> candidates = new LinkedHashSet<>(List.of(MAINTENANCE_DATABASE, "template1"));
        if (request.databaseName() != null && !request.databaseName().isBlank()) {
            candidates.add(request.databaseName().trim());
        }
        SQLException last = null;
        for (String database : candidates) {
            String url = JdbcUrls.withDatabase(env.getJdbcUrl(), database);
            try (Connection c = DriverManager.getConnection(url, connectionProperties(env));
                 Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(DATABASES_SQL)) {
                List<DiscoveredDatabase> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new DiscoveredDatabase(rs.getString("datname"), rs.getString("comment"),
                            rs.getBoolean("can_connect")));
                }
                return out;
            } catch (SQLException e) {
                last = e;
                // 3D000 = the database does not exist, 28000 / 42501 = no permission to connect: try the next one
                String sqlState = e.getSQLState();
                if (sqlState == null || !(sqlState.equals("3D000") || sqlState.equals("28000") || sqlState.equals("42501"))) {
                    break;
                }
            }
        }
        throw ApiException.badRequest("env.discoverFailed", serverLabel(env), last == null ? "" : last.getMessage());
    }

    /** User schemas of one database of the server, with comments (extension schemas are flagged). */
    public List<DiscoveredSchema> discoverSchemas(DiscoveryRequest request) {
        if (request == null || request.databaseName() == null || request.databaseName().isBlank()) {
            throw ApiException.badRequest("env.databaseRequired");
        }
        Environment env = discoveryEnvironment(request);
        String url = JdbcUrls.withDatabase(env.getJdbcUrl(), request.databaseName().trim());
        try (Connection c = DriverManager.getConnection(url, connectionProperties(env));
             PreparedStatement st = c.prepareStatement(SCHEMAS_SQL);
             ResultSet rs = st.executeQuery()) {
            List<DiscoveredSchema> out = new ArrayList<>();
            while (rs.next()) {
                out.add(new DiscoveredSchema(rs.getString("nspname"), rs.getString("comment"), rs.getString("owner"),
                        rs.getLong("table_count"), rs.getBoolean("extension")));
            }
            return out;
        } catch (SQLException e) {
            throw ApiException.badRequest("env.discoverFailed", serverLabel(env) + "/" + request.databaseName().trim(),
                    e.getMessage());
        }
    }

    private Environment discoveryEnvironment(DiscoveryRequest request) {
        if (request == null) {
            throw ApiException.badRequest("env.required");
        }
        Environment env = Environment.builder()
                .id(request.id())
                .host(trim(request.host()))
                .port(request.port())
                .databaseName(trim(request.databaseName()))
                .jdbcUrl(trim(request.jdbcUrl()))
                .username(trim(request.username()))
                .password(request.password())
                .build();
        if (env.getPassword() == null && env.getId() != null) {
            synchronized (lock) {
                state.getEnvironments().stream().filter(e -> e.getId().equals(env.getId())).findFirst()
                        .ifPresent(stored -> env.setPassword(stored.getPassword()));
            }
        }
        if (env.getDatabaseName() == null || env.getDatabaseName().isEmpty()) {
            env.setDatabaseName(MAINTENANCE_DATABASE);
        }
        env.setJdbcUrl(connectionUrl(env));
        return env;
    }

    /** Properties of a one-off connection (test / browse): timeouts, application name, user and password. */
    private Properties connectionProperties(Environment env) {
        Properties props = new Properties();
        props.setProperty("connectTimeout", Integer.toString(properties.getConnectTimeoutSeconds()));
        props.setProperty("loginTimeout", Integer.toString(properties.getConnectTimeoutSeconds()));
        props.setProperty("ApplicationName", "pg-git-vc");
        if (env.getUsername() != null && !env.getUsername().isEmpty()) {
            props.setProperty("user", env.getUsername());
        }
        if (env.getPassword() != null && !env.getPassword().isEmpty()) {
            props.setProperty("password", env.getPassword());
        }
        return props;
    }

    private static String serverLabel(Environment env) {
        JdbcUrls.Parts p = JdbcUrls.parse(env.getJdbcUrl());
        return p == null ? String.valueOf(env.getJdbcUrl()) : p.host() + ":" + p.port();
    }

    /**
     * JDBC URL of the connection settings: built from host / port / database when a host is given (the parameters of
     * the given JDBC URL are kept), otherwise the JDBC URL itself.
     */
    static String connectionUrl(Environment env) {
        String host = trim(env.getHost());
        String url = trim(env.getJdbcUrl());
        if (host != null && !host.isEmpty()) {
            Integer port = env.getPort();
            if (port != null && (port < 1 || port > 65535)) {
                throw ApiException.badRequest("env.portInvalid");
            }
            String database = trim(env.getDatabaseName());
            if (database == null || database.isEmpty()) {
                throw ApiException.badRequest("env.databaseRequired");
            }
            JdbcUrls.Parts old = JdbcUrls.parse(url);
            if (old != null && old.multiHost() && host.equals(old.host())
                    && java.util.Objects.equals(port == null ? Environment.DEFAULT_PORT : port, old.port())) {
                // a multi-host URL (failover) cannot be described by one host: keep its host list, switch the database
                return JdbcUrls.withDatabase(url, database);
            }
            return JdbcUrls.build(host, port, database, old == null ? null : old.params());
        }
        if (url == null || url.isEmpty()) {
            throw ApiException.badRequest("env.hostRequired");
        }
        if (!url.startsWith(JdbcUrls.PREFIX)) {
            throw ApiException.badRequest("env.jdbcUrlInvalid");
        }
        return url;
    }

    /** Fills host / port / database from the JDBC URL (environments of older versions have only the URL). */
    private static void fillConnectionParts(Environment env) {
        JdbcUrls.Parts p = JdbcUrls.parse(env.getJdbcUrl());
        if (p != null) {
            env.setHost(p.host());
            env.setPort(p.port());
            env.setDatabaseName(p.database());
        }
    }

    /** Picked schemas without duplicates and blanks, or {@code [ALL_USER_SCHEMAS]}. */
    static List<String> normalizeSchemas(List<String> schemas) {
        if (schemas == null || schemas.isEmpty()) {
            return new ArrayList<>(List.of(Environment.ALL_USER_SCHEMAS));
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String s : schemas) {
            if (s == null) {
                continue;
            }
            String name = s.trim();
            if (name.equals(Environment.ALL_USER_SCHEMAS)) {
                return new ArrayList<>(List.of(Environment.ALL_USER_SCHEMAS));
            }
            if (!name.isEmpty()) {
                out.add(name);
            }
        }
        if (out.isEmpty()) {
            throw ApiException.badRequest("env.schemasRequired");
        }
        return new ArrayList<>(out);
    }

    // ------------------------------------------------------------------ validation & persistence

    private Environment find(String id) {
        return state.getEnvironments().stream().filter(e -> e.getId().equals(id)).findFirst()
                .orElseThrow(() -> ApiException.notFound("env.notFound", id));
    }

    private Environment normalize(Environment input, Environment existing) {
        if (input == null) {
            throw ApiException.badRequest("env.required");
        }
        String name = trim(input.getName());
        if (name == null || name.isEmpty()) {
            throw ApiException.badRequest("env.nameRequired");
        }
        String url = connectionUrl(input);
        String schema = trim(input.getSchemaName());
        if (schema == null || schema.isEmpty()) {
            schema = Environment.DEFAULT_SCHEMA;
        }
        String color = trim(input.getColorBadge());
        if (color == null || color.isEmpty()) {
            color = existing != null ? existing.getColorBadge() : PALETTE[state.getEnvironments().size() % PALETTE.length];
        } else if (!COLOR.matcher(color).matches()) {
            throw ApiException.badRequest("env.colorInvalid");
        }
        String password = input.getPassword();
        if (password == null) {
            password = existing != null ? existing.getPassword() : "";
        }
        List<String> tracked = input.getTrackedSchemas() == null && existing != null
                ? existing.getTrackedSchemas() : input.getTrackedSchemas();
        Environment env = Environment.builder()
                .name(name)
                .jdbcUrl(url)
                .username(trim(input.getUsername()) == null ? "" : trim(input.getUsername()))
                .password(password)
                .schemaName(schema)
                .trackedSchemas(normalizeSchemas(tracked))
                .colorBadge(color.toLowerCase(Locale.ROOT))
                .build();
        fillConnectionParts(env);
        return env;
    }

    private String uniqueId(String name) {
        String base = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (base.isEmpty()) {
            base = "env";
        } else if (RESERVED_IDS.contains(base)) {
            // would clash with /api/environments/active and /api/environments/test
            base = "env-" + base;
        }
        String id = base;
        for (int n = 2; idTaken(id); n++) {
            id = base + "-" + n;
        }
        return id;
    }

    private boolean idTaken(String id) {
        return state.getEnvironments().stream().anyMatch(e -> e.getId().equals(id));
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private EnvironmentsFile load() {
        if (!Files.exists(file)) {
            return new EnvironmentsFile();
        }
        try {
            String json = Files.readString(file);
            if (json.isBlank()) {
                return new EnvironmentsFile();
            }
            JsonNode root = objectMapper.readTree(json);
            EnvironmentsFile loaded;
            if (root.isArray()) {
                // plain list of environments (hand-written file)
                loaded = new EnvironmentsFile(null, new ArrayList<>(List.of(objectMapper.treeToValue(root, Environment[].class))));
            } else {
                loaded = objectMapper.treeToValue(root, EnvironmentsFile.class);
            }
            if (loaded.getEnvironments() == null) {
                loaded.setEnvironments(new ArrayList<>());
            }
            for (Environment e : loaded.getEnvironments()) {
                if (e.getSchemaName() == null || e.getSchemaName().isBlank()) {
                    e.setSchemaName(Environment.DEFAULT_SCHEMA);
                }
                if (e.getId() == null || e.getId().isBlank()) {
                    throw new IllegalStateException("Every environment in " + file + " needs an \"id\"");
                }
                if ((e.getJdbcUrl() == null || e.getJdbcUrl().isBlank()) && e.getHost() != null && !e.getHost().isBlank()
                        && e.getDatabaseName() != null) {
                    // hand-written file with host / port / database only
                    e.setJdbcUrl(JdbcUrls.build(e.getHost(), e.getPort(), e.getDatabaseName(), null));
                }
                fillConnectionParts(e);
                e.setTrackedSchemas(normalizeSchemas(e.getTrackedSchemas()));
            }
            String active = loaded.getActiveEnvironmentId();
            if (active == null || loaded.getEnvironments().stream().noneMatch(e -> e.getId().equals(active))) {
                loaded.setActiveEnvironmentId(loaded.getEnvironments().isEmpty() ? null : loaded.getEnvironments().get(0).getId());
            }
            return loaded;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    private void persist() {
        try {
            Path dir = file.getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), state);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file + ": " + e.getMessage(), e);
        }
    }
}
