package com.gossiplog.chat.auth;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Single source of truth for the gateway-injected auth header names, plus the
 * shared X-Username decode. AuthInterceptor + AuthenticatedUserResolver read
 * the authenticated principal ({@link #authenticatedUserId()}); JwtAuthFilter
 * + WebSocketConfig read X-Username and decode it via
 * {@link #decodeUsername(String)}. Keeping the names/decoding here avoids the
 * silent split that a stray literal or duplicated decode would cause.
 */
public final class AuthHeaders {

    public static final String X_USER_ID = "X-User-Id";
    public static final String X_USERNAME = "X-Username";
    public static final String X_GATEWAY_SECRET = "X-Gateway-Secret";

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

    /**
     * The user id {@code JwtAuthFilter} authenticated for this request (gateway
     * headers with a valid secret, or a verified JWT), or {@code null}. A raw
     * X-User-Id header alone is never an identity.
     */
    public static String authenticatedUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return auth.getPrincipal() instanceof String id && !id.isBlank() ? id : null;
    }

    /**
     * Constant-time check of an incoming X-Gateway-Secret value. Fails closed:
     * an unconfigured (null/blank) expected secret never matches.
     */
    public static boolean gatewaySecretMatches(String expected, String incoming) {
        if (expected == null || expected.isBlank() || incoming == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                incoming.getBytes(StandardCharsets.UTF_8));
    }
}
