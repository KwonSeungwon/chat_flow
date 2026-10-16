package com.chatflow.aisummary.exception;

/** No gateway-injected {@code X-User-Id} on the request → 401. */
public class UnauthenticatedException extends RuntimeException {
    public UnauthenticatedException(String message) {
        super(message);
    }
}
