package com.chatflow.chat.exception;

/**
 * 403 — caller is authenticated but does not have access (not a room member).
 */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
