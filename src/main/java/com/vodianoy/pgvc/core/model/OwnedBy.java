package com.vodianoy.pgvc.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Column that owns a sequence (ALTER SEQUENCE ... OWNED BY). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OwnedBy(String schema, String table, String column) {
}
