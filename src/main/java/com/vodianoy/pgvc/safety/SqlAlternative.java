package com.vodianoy.pgvc.safety;

/** A safer replacement for one statement (exact text as it appears in the script). */
public record SqlAlternative(String statement, String replacement) {
}
