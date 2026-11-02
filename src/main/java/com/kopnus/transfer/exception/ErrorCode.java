package com.kopnus.transfer.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Request is not valid"),
    SAME_ACCOUNT(HttpStatus.BAD_REQUEST, "Source and destination account must be different"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Authentication is required"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Username or password is incorrect"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Access is denied"),
    ACCOUNT_ACCESS_DENIED(HttpStatus.FORBIDDEN, "Source account does not belong to the authenticated customer"),
    ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "Account not found"),
    IDEMPOTENCY_KEY_CONFLICT(HttpStatus.CONFLICT, "Idempotency-Key was already used with a different request"),
    ACCOUNT_INACTIVE(HttpStatus.UNPROCESSABLE_CONTENT, "Account is not active"),
    INSUFFICIENT_BALANCE(HttpStatus.UNPROCESSABLE_CONTENT, "Insufficient balance"),
    RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests, please try again later"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred"),
    SERVICE_BUSY(HttpStatus.SERVICE_UNAVAILABLE, "Service is busy, no money was moved, please retry");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }
}
