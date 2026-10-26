package com.vodianoy.pgvc.core.env;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.vodianoy.pgvc.core.util.Json;
import com.vodianoy.pgvc.core.util.PgvcException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Environments persisted in a local JSON file (./pgvc-environments.json next to the jar by default). */
public final class EnvironmentStore {

    public static final String MASKED_PASSWORD = "********";
    private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");
    private static final String[] PALETTE = {"#22c55e", "#3b82f6", "#f59e0b", "#ef4444", "#a855f7", "#14b8a6", "#ec4899"};

    private final Path file;
    private final List<EnvironmentConfig> environments = new ArrayList<>();

    public EnvironmentStore(Path file) {
        this.file = file.toAbsolutePath().normalize();
        load();
    }

    public Path file() {
        return file;
    }

    public synchronized List<EnvironmentConfig> list() {
        return List.copyOf(environments);
    }

    public synchronized EnvironmentConfig get(String id) {
        return environments.stream().filter(e -> e.id().equals(id)).findFirst()
                .orElseThrow(() -> PgvcException.notFound("Environment not found: " + id));
    }

    /** Creates or updates an environment. A masked password keeps the stored one. */
    public synchronized EnvironmentConfig save(EnvironmentConfig input) {
        if (input.name() == null || input.name().isBlank()) {
            throw PgvcException.badRequest("Environment name is required");
        }
        if (input.jdbcUrl() == null || !input.jdbcUrl().startsWith("jdbc:postgresql:")) {
            throw PgvcException.badRequest("JDBC URL must start with jdbc:postgresql:");
        }
        String id = input.id() == null || input.id().isBlank() ? uniqueId(input.name()) : input.id();
        EnvironmentConfig existing = environments.stream().filter(e -> e.id().equals(id)).findFirst().orElse(null);
        String password = input.password();
        if (existing != null && (password == null || MASKED_PASSWORD.equals(password))) {
            password = existing.password();
        }
        String color = input.color() != null && COLOR.matcher(input.color()).matches()
                ? input.color() : PALETTE[environments.size() % PALETTE.length];
        EnvironmentConfig saved = new EnvironmentConfig(id, input.name().trim(), color, input.jdbcUrl().trim(),
                input.username() == null ? "" : input.username().trim(), password == null ? "" : password);
        if (existing != null) {
            environments.set(environments.indexOf(existing), saved);
        } else {
            environments.add(saved);
        }
        persist();
        return saved;
    }

    public synchronized void delete(String id) {
        if (!environments.removeIf(e -> e.id().equals(id))) {
            throw PgvcException.notFound("Environment not found: " + id);
        }
        persist();
    }

    /** Resolves a masked password of an unsaved/edited config against the stored one (for "Test connection"). */
    public synchronized EnvironmentConfig resolvePassword(EnvironmentConfig input) {
        if (input.id() != null && MASKED_PASSWORD.equals(input.password())) {
            for (EnvironmentConfig e : environments) {
                if (e.id().equals(input.id())) {
                    return new EnvironmentConfig(input.id(), input.name(), input.color(), input.jdbcUrl(),
                            input.username(), e.password());
                }
            }
        }
        return input;
    }

    private String uniqueId(String name) {
        String base = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (base.isEmpty()) {
            base = "env";
        }
        String id = base;
        int n = 2;
        while (true) {
            String candidate = id;
            if (environments.stream().noneMatch(e -> e.id().equals(candidate))) {
                return candidate;
            }
            id = base + "-" + n++;
        }
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            if (json.isBlank()) {
                return;
            }
            List<EnvironmentConfig> loaded = Json.MAPPER.readValue(json, new TypeReference<List<EnvironmentConfig>>() {
            });
            environments.addAll(loaded);
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
            Files.writeString(tmp, Json.MAPPER.copy().enable(SerializationFeature.INDENT_OUTPUT)
                    .writeValueAsString(environments), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file + ": " + e.getMessage(), e);
        }
    }
}
