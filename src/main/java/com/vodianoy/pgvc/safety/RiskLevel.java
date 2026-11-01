package com.vodianoy.pgvc.safety;

/** How risky a statement is for a database in use: green / yellow / red in the UI. */
public enum RiskLevel {
    SAFE,
    WARNING,
    DANGEROUS;

    public RiskLevel max(RiskLevel other) {
        return other != null && other.ordinal() > ordinal() ? other : this;
    }
}
