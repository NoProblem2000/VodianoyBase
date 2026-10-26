package com.vodianoy.pgvc.common;

/** JSON body of every error response. */
public record ApiError(int status, String error, String message) {
}
