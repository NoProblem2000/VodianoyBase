package com.vodianoy.pgvc.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.Locale;

/**
 * Error with an HTTP status that is reported to the client as {"status", "error", "message"}.
 * The message is a key of {@code i18n/messages_*.properties} with arguments: {@link #getMessage()} is the English
 * text (logs, tests), the REST layer renders it in the language of the request ({@code Accept-Language}).
 */
@Getter
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final transient Object[] args;

    public ApiException(HttpStatus status, String code, Object... args) {
        super(Messages.format(Locale.ENGLISH, code, args));
        this.status = status;
        this.code = code;
        this.args = args == null ? new Object[0] : args.clone();
    }

    public static ApiException badRequest(String code, Object... args) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, args);
    }

    public static ApiException notFound(String code, Object... args) {
        return new ApiException(HttpStatus.NOT_FOUND, code, args);
    }

    public static ApiException conflict(String code, Object... args) {
        return new ApiException(HttpStatus.CONFLICT, code, args);
    }
}
