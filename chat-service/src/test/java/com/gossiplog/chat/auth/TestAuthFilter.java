package com.gossiplog.chat.auth;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

/**
 * Standalone-MockMvc stand-in for {@code JwtAuthFilter}: a request carrying
 * {@code X-User-Id} is treated as gateway-authenticated as that user.
 */
public final class TestAuthFilter {

    private TestAuthFilter() {}

    public static Filter fromUserIdHeader() {
        return (request, response, chain) -> {
            String userId = ((HttpServletRequest) request).getHeader(AuthHeaders.X_USER_ID);
            if (userId != null) {
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(userId, null, List.of()));
            }
            try {
                chain.doFilter(request, response);
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
    }
}
