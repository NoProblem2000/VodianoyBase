package com.vodianoy.pgvc.core.util;

/** Application error with a category that the web layer maps to an HTTP status. */
public class PgvcException extends RuntimeException {

    public enum Kind { BAD_REQUEST, NOT_FOUND, CONFLICT, DATABASE, UNAVAILABLE }

    private final Kind kind;

    public PgvcException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public PgvcException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public static PgvcException badRequest(String message) {
        return new PgvcException(Kind.BAD_REQUEST, message);
    }

    public static PgvcException notFound(String message) {
        return new PgvcException(Kind.NOT_FOUND, message);
    }

    public static PgvcException conflict(String message) {
        return new PgvcException(Kind.CONFLICT, message);
    }
}
