package com.chatflow.chat.exception;

/**
 * 401 — caller is not authenticated (missing/blank X-User-Id).
 */
public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String message) {
        super(message);
    }
}
