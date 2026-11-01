package com.vodianoy.pgvc.safety;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Safety of one statement, one change or a whole script on a database in use.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SafetyReport {

    @Builder.Default
    private RiskLevel riskLevel = RiskLevel.SAFE;

    /** Strongest table lock taken (null: no lock on an existing table, e.g. a new table). */
    private LockLevel lockType;

    /** The table (or index / partition) files are rewritten: time and disk proportional to the table size. */
    private boolean causesTableRewrite;

    /** Rows or columns are removed, or values may not survive a type change. */
    private boolean causesDataLoss;

    /** The statement scans or rewrites the table or builds an index (duration depends on the size). */
    private boolean longRunning;

    /** Must run outside of a transaction (CREATE INDEX CONCURRENTLY ...): a step of its own. */
    private boolean nonTransactional;

    /** The table the statement works on, {@code schema.table}, and its size statistics. */
    private String table;
    private Long estimatedRows;
    private Long totalSizeBytes;
    private String prettySize;

    /** Rough duration estimate in seconds from the size (null when unknown or instant). */
    private Double estimatedSeconds;

    /** Localized text of the estimate, e.g. "≈ 25 s: table rewrite of 1229 MB / 4.5M rows". */
    private String estimatedDurationHint;

    /** Localized explanations. */
    @Builder.Default
    private List<String> notes = new ArrayList<>();

    /** Safer variant of the statement (or of the whole change), for "Use Zero-Downtime Pattern". */
    private String zeroDowntimeAlternativeSql;

    /** Exact statement replacements behind {@link #zeroDowntimeAlternativeSql}. */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @Builder.Default
    private List<SqlAlternative> alternatives = new ArrayList<>();
}
