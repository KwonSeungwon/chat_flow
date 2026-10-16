package com.chatflow.aisummary.client;

/**
 * chat-service membership lookup failed (timeout, connection error, non-2xx/404).
 * Mapped to 503 — authorization never fails open.
 */
public class MembershipCheckUnavailableException extends RuntimeException {
    public MembershipCheckUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
