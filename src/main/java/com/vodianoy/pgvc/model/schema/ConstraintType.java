package com.vodianoy.pgvc.model.schema;

/** pg_constraint.contype values that are version controlled. */
public enum ConstraintType {
    PRIMARY_KEY("p"),
    FOREIGN_KEY("f"),
    UNIQUE("u"),
    CHECK("c"),
    EXCLUDE("x");

    private final String code;

    ConstraintType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static ConstraintType fromCode(String code) {
        for (ConstraintType t : values()) {
            if (t.code.equals(code)) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unsupported constraint type: " + code);
    }
}
