package com.chatflow.chat.config;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    @Value("${GATEWAY_INTERNAL_SECRET:}")
    private String gatewayInternalSecret;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // Gateway가 주입한 X-User-Id 헤더로 인증 — X-Gateway-Secret으로 게이트웨이 경유 검증
        String xUserId = request.getHeader("X-User-Id");
        String xUsername = request.getHeader("X-Username");
        // URL-decode username (gateway URL-encodes Korean chars for HTTP header safety)
        if (xUsername != null) {
            try { xUsername = java.net.URLDecoder.decode(xUsername, java.nio.charset.StandardCharsets.UTF_8); } catch (Exception ignored) {}
        }
        if (xUserId != null && xUsername != null) {
            if (isGatewaySecretValid(request)) {
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        xUserId, null, Collections.emptyList());
                auth.setDetails(Map.of("userId", xUserId, "username", xUsername));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } else {
                log.warn("X-User-Id 헤더 존재하지만 X-Gateway-Secret 검증 실패 — 헤더 스푸핑 의심: uri={}", request.getRequestURI());
            }
            // Wrap request so @RequestHeader("X-Username") also gets decoded value
            final String decodedUsername = xUsername;
            filterChain.doFilter(new jakarta.servlet.http.HttpServletRequestWrapper(request) {
                @Override
                public String getHeader(String name) {
                    if ("X-Username".equalsIgnoreCase(name)) return decodedUsername;
                    return super.getHeader(name);
                }
                @Override
                public java.util.Enumeration<String> getHeaders(String name) {
                    if ("X-Username".equalsIgnoreCase(name)) {
                        return java.util.Collections.enumeration(java.util.List.of(decodedUsername));
                    }
                    return super.getHeaders(name);
                }
            }, response);
            return;
        }

        String token = extractToken(request);

        if (token != null) {
            try {
                Claims claims = jwtUtil.parseToken(token);
                String userId = claims.getSubject();
                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(userId, null, List.of());
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (Exception e) {
                SecurityContextHolder.clearContext();
                log.debug("JWT 검증 실패: {}", e.getMessage());
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * X-Gateway-Secret 헤더를 상수 시간 비교로 검증.
     * 시크릿이 미설정(빈 문자열)이면 검증 우회 — 로컬/테스트 환경 호환성 유지.
     */
    private boolean isGatewaySecretValid(HttpServletRequest request) {
        if (gatewayInternalSecret == null || gatewayInternalSecret.isBlank()) {
            return true; // 미설정 시 검증 스킵 (로컬/테스트)
        }
        String incoming = request.getHeader("X-Gateway-Secret");
        if (incoming == null) {
            return false;
        }
        return MessageDigest.isEqual(
                gatewayInternalSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                incoming.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private String extractToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (bearer != null && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        // 쿼리 파라미터 토큰은 WebSocket 핸드셰이크 경로로만 허용
        String uri = request.getRequestURI();
        if (uri != null && (uri.startsWith("/ws") || "websocket".equalsIgnoreCase(request.getHeader("Upgrade")))) {
            return request.getParameter("token");
        }
        return null;
    }
}
