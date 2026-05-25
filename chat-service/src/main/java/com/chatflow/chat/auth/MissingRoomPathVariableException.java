package com.chatflow.chat.auth;

/**
 * Thrown by AuthInterceptor when a @RequireMember-annotated controller
 * method's declared pathVar cannot be resolved from the request URI's
 * path-variable map. This is a developer-error condition (mis-annotation
 * or routing mismatch) — fail loud here rather than smuggle null into
 * the membership guard's data layer.
 *
 * Intentionally extends RuntimeException (not IllegalStateException) so
 * that GlobalExceptionHandler.handleIllegalState (which maps to 429
 * QUOTA_EXCEEDED for legitimate resource-cap cases) does not catch it.
 * Without a dedicated handler, Spring will default to 500 — which is
 * the right outcome for a developer-error condition.
 */
public class MissingRoomPathVariableException extends RuntimeException {
    public MissingRoomPathVariableException(String message) {
        super(message);
    }
}
