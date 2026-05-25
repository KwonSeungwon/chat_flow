package com.chatflow.chat.result;

import org.springframework.http.HttpStatus;

/**
 * Single source-of-truth for failure modes returned by Result-bearing
 * services. The HTTP status here is the default response code — controllers
 * can override on a per-endpoint basis (e.g. invite token returns 410 GONE
 * instead of the default 404 NOT_FOUND).
 */
public enum ChatErrorCode {
    NOT_FOUND(HttpStatus.NOT_FOUND),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    MUTED(HttpStatus.LOCKED),
    DELETED(HttpStatus.GONE),
    ROOM_FULL(HttpStatus.BAD_REQUEST),
    INVALID_INPUT(HttpStatus.BAD_REQUEST),
    GONE(HttpStatus.GONE),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus defaultHttpStatus;

    ChatErrorCode(HttpStatus defaultHttpStatus) {
        this.defaultHttpStatus = defaultHttpStatus;
    }

    public HttpStatus defaultHttpStatus() {
        return defaultHttpStatus;
    }
}
