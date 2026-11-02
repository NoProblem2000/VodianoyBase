package com.vodianoy.pgvc.settings;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.vodianoy.pgvc.diff.DiffIgnoreRules;

/**
 * Contents of {@code pgvc-settings.json}.
 *
 * @param ignoreRules default rules of every schema comparison
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PgVcSettings(DiffIgnoreRules ignoreRules) {

    public static PgVcSettings defaults() {
        return new PgVcSettings(DiffIgnoreRules.DEFAULTS);
    }
}
