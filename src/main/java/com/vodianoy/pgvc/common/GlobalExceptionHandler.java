package com.vodianoy.pgvc.common;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Turns exceptions into {@link ApiError} responses. Messages of {@link ApiException} are rendered in the language of
 * the request ({@code Accept-Language}); texts coming from PostgreSQL itself are passed through as they are.
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final MessageSource messages;

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException e) {
        return respond(e.getStatus(), messages.getMessage(e.getCode(), e.getArgs(), e.getMessage(),
                LocaleContextHolder.getLocale()));
    }

    /** A failed rollback must not hide the error that caused it. */
    @ExceptionHandler(TransactionSystemException.class)
    public ResponseEntity<ApiError> handleTransaction(TransactionSystemException e) {
        Throwable original = e.getApplicationException();
        if (original instanceof ApiException api) {
            return handleApi(api);
        }
        if (original != null) {
            return respond(HttpStatus.UNPROCESSABLE_ENTITY, rootMessage(original));
        }
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, rootMessage(e));
    }

    @ExceptionHandler(CannotGetJdbcConnectionException.class)
    public ResponseEntity<ApiError> handleNoConnection(CannotGetJdbcConnectionException e) {
        return respond(HttpStatus.SERVICE_UNAVAILABLE, messages.getMessage("error.db.connection",
                new Object[]{rootMessage(e)}, LocaleContextHolder.getLocale()));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> handleDatabase(DataAccessException e) {
        return respond(HttpStatus.UNPROCESSABLE_ENTITY, rootMessage(e));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> handleBadRequest(Exception e) {
        return respond(HttpStatus.BAD_REQUEST, messages.getMessage("error.request.invalid",
                new Object[]{e.getMessage()}, LocaleContextHolder.getLocale()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception e) {
        log.error("Unexpected error", e);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, rootMessage(e));
    }

    static String rootMessage(Throwable e) {
        Throwable root = NestedExceptionUtils.getMostSpecificCause(e);
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    private static ResponseEntity<ApiError> respond(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new ApiError(status.value(), status.getReasonPhrase(), message));
    }
}
