package com.vodianoy.pgvc.safety;

import com.fasterxml.jackson.annotation.JsonValue;

/** PostgreSQL table-level lock modes, weakest first. */
public enum LockLevel {
    ACCESS_SHARE("ACCESS SHARE"),
    ROW_SHARE("ROW SHARE"),
    ROW_EXCLUSIVE("ROW EXCLUSIVE"),
    SHARE_UPDATE_EXCLUSIVE("SHARE UPDATE EXCLUSIVE"),
    SHARE("SHARE"),
    SHARE_ROW_EXCLUSIVE("SHARE ROW EXCLUSIVE"),
    EXCLUSIVE("EXCLUSIVE"),
    ACCESS_EXCLUSIVE("ACCESS EXCLUSIVE");

    private final String label;

    LockLevel(String label) {
        this.label = label;
    }

    @JsonValue
    public String label() {
        return label;
    }

    /** SHARE and stronger block INSERT / UPDATE / DELETE for as long as they are held. */
    public boolean blocksWrites() {
        return ordinal() >= SHARE.ordinal();
    }

    /** ACCESS EXCLUSIVE also blocks SELECT. */
    public boolean blocksReads() {
        return this == ACCESS_EXCLUSIVE;
    }

    public static LockLevel max(LockLevel a, LockLevel b) {
        if (a == null) {
            return b;
        }
        return b == null || a.ordinal() >= b.ordinal() ? a : b;
    }
}
