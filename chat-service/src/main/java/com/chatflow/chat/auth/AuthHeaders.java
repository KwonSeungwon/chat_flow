package com.chatflow.chat.auth;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Single source of truth for the gateway-injected auth header names, plus the
 * shared X-Username decode. AuthInterceptor + AuthenticatedUserResolver read
 * X-User-Id; JwtAuthFilter + WebSocketConfig read X-Username and decode it via
 * {@link #decodeUsername(String)}. Keeping the names/decoding here avoids the
 * silent split that a stray literal or duplicated decode would cause.
 */
public final class AuthHeaders {

    public static final String X_USER_ID = "X-User-Id";
    public static final String X_USERNAME = "X-Username";

    private AuthHeaders() {}

    /**
     * URL-decode a raw X-Username header value.
     * Gateway URL-encodes Korean characters for HTTP header safety;
     * this reverses that encoding.
     *
     * @param raw the raw (possibly URL-encoded) header value, may be null
     * @return decoded username, or null if raw is null, or the raw value
     *         unchanged if decoding fails (malformed percent-encoding)
     */
    public static String decodeUsername(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return raw;
        }
    }
}
