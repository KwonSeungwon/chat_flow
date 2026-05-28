package com.chatflow.chat.auth;

/**
 * Single source of truth for HTTP header names used by the auth layer.
 * AuthInterceptor + AuthenticatedUserResolver both read X-User-Id; the
 * constant must be aligned to avoid silent split.
 */
public final class AuthHeaders {

    public static final String X_USER_ID = "X-User-Id";

    private AuthHeaders() {}
}
