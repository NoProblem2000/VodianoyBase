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
import java.util.ArrayList;
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
    private static final Pattern COLOR =Pattern.compile("#[0-9a-fA-F]{6}");
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
        if (candidate.getJdbcUrl() == null || !candidate.getJdbcUrl().startsWith("jdbc:postgresql:")) {
            throw ApiException.badRequest("JDBC URL must start with jdbc:postgresql:");
        }
        Environment env = candidate.toBuilder().build();
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

    // ------------------------------------------------------------------ validation & persistence

    private Environment find(String id) {
        return state.getEnvironments().stream().filter(e -> e.getId().equals(id)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Environment not found: " + id));
    }

    private Environment normalize(Environment input, Environment existing) {
        if (input == null) {
            throw ApiException.badRequest("Environment is required");
        }
        String name = trim(input.getName());
        if (name == null || name.isEmpty()) {
            throw ApiException.badRequest("Environment name is required");
        }
        String url = trim(input.getJdbcUrl());
        if (url == null || !url.startsWith("jdbc:postgresql:")) {
            throw ApiException.badRequest("JDBC URL must start with jdbc:postgresql:");
        }
        String schema = trim(input.getSchemaName());
        if (schema == null || schema.isEmpty()) {
            schema = Environment.DEFAULT_SCHEMA;
        }
        String color = trim(input.getColorBadge());
        if (color == null || color.isEmpty()) {
            color = existing != null ? existing.getColorBadge() : PALETTE[state.getEnvironments().size() % PALETTE.length];
        } else if (!COLOR.matcher(color).matches()) {
            throw ApiException.badRequest("colorBadge must look like #22c55e");
        }
        String password = input.getPassword();
        if (password == null) {
            password = existing != null ? existing.getPassword() : "";
        }
        return Environment.builder()
                .name(name)
                .jdbcUrl(url)
                .username(trim(input.getUsername()) == null ? "" : trim(input.getUsername()))
                .password(password)
                .schemaName(schema)
                .colorBadge(color.toLowerCase(Locale.ROOT))
                .build();
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
