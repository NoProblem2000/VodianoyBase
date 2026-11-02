package com.vodianoy.pgvc.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vodianoy.pgvc.config.PgVcProperties;
import com.vodianoy.pgvc.diff.DiffIgnoreRules;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Installation settings in {@code pgvc-settings.json} (next to {@code pgvc-environments.json} unless
 * {@code pgvc.settings-file} says otherwise): the default {@link DiffIgnoreRules} used by every comparison that
 * passes no rules of its own. A missing file means the built-in defaults (owners, grants and tablespaces ignored).
 */
@Slf4j
@Service
public class DiffSettingsService {

    private final Path file;
    private final ObjectMapper objectMapper;
    private final Object lock = new Object();
    /** Guarded by {@link #lock}. */
    private PgVcSettings settings;

    public DiffSettingsService(PgVcProperties properties, ObjectMapper objectMapper) {
        String configured = properties.getSettingsFile();
        Path environments = Path.of(properties.getEnvironmentsFile()).toAbsolutePath().normalize();
        this.file = configured == null || configured.isBlank()
                ? environments.resolveSibling("pgvc-settings.json")
                : Path.of(configured).toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
        this.settings = load();
    }

    public Path getFile() {
        return file;
    }

    public DiffIgnoreRules getIgnoreRules() {
        synchronized (lock) {
            return settings.ignoreRules() == null ? DiffIgnoreRules.DEFAULTS : settings.ignoreRules();
        }
    }

    /** Validates and saves new default rules; returns them as stored. */
    public DiffIgnoreRules saveIgnoreRules(DiffIgnoreRules rules) {
        DiffIgnoreRules valid = (rules == null ? DiffIgnoreRules.DEFAULTS : rules).validated();
        synchronized (lock) {
            PgVcSettings next = new PgVcSettings(valid);
            persist(next);
            settings = next;
        }
        log.info("Default ignore rules saved: {}", valid);
        return valid;
    }

    private PgVcSettings load() {
        if (!Files.exists(file)) {
            return PgVcSettings.defaults();
        }
        try {
            PgVcSettings loaded = objectMapper.readValue(file.toFile(), PgVcSettings.class);
            return loaded == null || loaded.ignoreRules() == null ? PgVcSettings.defaults() : loaded;
        } catch (IOException e) {
            log.warn("Cannot read {} ({}): using the default settings", file, e.getMessage());
            return PgVcSettings.defaults();
        }
    }

    private void persist(PgVcSettings next) {
        try {
            Path dir = file.getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), next);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file + ": " + e.getMessage(), e);
        }
    }
}
