package com.vodianoy.pgvc.model.schema;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Rows of a table tracked as reference data (a dictionary), ordered by its PRIMARY KEY. Every value is the text output
 * of the column ({@code value::text}); null is SQL NULL. Generated columns are not stored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ReferenceDataModel {

    /** At most this many rows are tracked per table. */
    public static final int MAX_ROWS = 10_000;

    /** Stored columns in attnum order. */
    @Builder.Default
    private List<String> columns = new ArrayList<>();

    /** format_type of each column (same order), used to cast the values back. */
    @Builder.Default
    private List<String> columnTypes = new ArrayList<>();

    /** PRIMARY KEY columns in key order. */
    @Builder.Default
    private List<String> primaryKey = new ArrayList<>();

    /** Rows in PRIMARY KEY order, values in {@link #columns} order. Empty when {@link #truncated}. */
    @Builder.Default
    private List<List<String>> rows = new ArrayList<>();

    /** Number of rows in the table (at most {@link #MAX_ROWS} + 1). */
    private long rowCount;

    /** The table has more than {@link #MAX_ROWS} rows: the rows are not stored and not compared. */
    private boolean truncated;

    /** SHA-256 of {@link #canonicalJson(List)} of the rows. */
    private String rowsHash;

    /** Canonical JSON of the rows: {@code [["1","Active"],["2",null]]}, no whitespace. */
    public static String canonicalJson(List<List<String>> rows) {
        StringBuilder sb = new StringBuilder("[");
        for (int r = 0; r < rows.size(); r++) {
            if (r > 0) {
                sb.append(',');
            }
            sb.append('[');
            List<String> row = rows.get(r);
            for (int c = 0; c < row.size(); c++) {
                if (c > 0) {
                    sb.append(',');
                }
                appendJsonString(sb, row.get(c));
            }
            sb.append(']');
        }
        return sb.append(']').toString();
    }

    public static String hash(List<List<String>> rows) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(canonicalJson(rows).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void appendJsonString(StringBuilder sb, String value) {
        if (value == null) {
            sb.append("null");
            return;
        }
        sb.append('"');
        for (char ch : value.toCharArray()) {
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        sb.append('"');
    }

    /** Position of a column in {@link #columns}, -1 when missing. */
    @JsonIgnore
    public int indexOf(String column) {
        return columns.indexOf(column);
    }
}
