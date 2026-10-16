package com.chatflow.gateway.security;

import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

@Slf4j
public class JwtAuthenticationWebFilter implements WebFilter {

    private final JwtUtil jwtUtil;
    private final TokenBlacklistService tokenBlacklistService;

    private String gatewayInternalSecret = "";

    public JwtAuthenticationWebFilter(JwtUtil jwtUtil, TokenBlacklistService tokenBlacklistService) {
        this.jwtUtil = jwtUtil;
        this.tokenBlacklistService = tokenBlacklistService;
    }

    public JwtAuthenticationWebFilter(JwtUtil jwtUtil, TokenBlacklistService tokenBlacklistService,
                                      String gatewayInternalSecret) {
        this(jwtUtil, tokenBlacklistService);
        this.gatewayInternalSecret = gatewayInternalSecret;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // 클라이언트가 X-User-Id/X-Username 헤더를 직접 주입하는 것을 방지
        ServerHttpRequest sanitizedRequest = exchange.getRequest().mutate()
                .headers(h -> {
                    h.remove("X-User-Id");
                    h.remove("X-Username");
                })
                .build();
        ServerWebExchange sanitizedExchange = exchange.mutate().request(sanitizedRequest).build();

        String token = extractToken(sanitizedExchange.getRequest());

        if (token == null) {
            return chain.filter(sanitizedExchange);
        }

        // Parse and verify the JWT once — reuse Claims for blacklist check + header injection
        Optional<Claims> maybeClaims = jwtUtil.tryParse(token);
        if (maybeClaims.isEmpty()) {
            return chain.filter(sanitizedExchange);
        }

        Claims claims = maybeClaims.get();
        String jti = claims.getId();

        // JTI가 없는 레거시 토큰은 서명 검증만으로 통과
        if (jti == null) {
            return authenticateAndContinue(sanitizedExchange, chain, claims);
        }

        // Redis 블랙리스트 확인 (리액티브)
        return tokenBlacklistService.isBlacklisted(jti)
                .flatMap(blacklisted -> {
                    if (blacklisted) {
                        log.debug("블랙리스트 토큰 거부: jti={}", jti);
                        return chain.filter(sanitizedExchange); // 인증 없이 통과 → Security가 401 반환
                    }
                    return authenticateAndContinue(sanitizedExchange, chain, claims);
                });
    }

    private Mono<Void> authenticateAndContinue(ServerWebExchange exchange, WebFilterChain chain, Claims claims) {
        String userId = claims.getSubject();
        String username = claims.get("username", String.class);

        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(userId, null, List.of());

        // 다운스트림 서비스에 사용자 정보 헤더 주입
        ServerHttpRequest.Builder reqBuilder = exchange.getRequest().mutate()
                .header("X-User-Id", userId)
                .header("X-Username", java.net.URLEncoder.encode(username, java.nio.charset.StandardCharsets.UTF_8));
        // 내부 시크릿 헤더 추가 — 다운스트림이 Gateway 경유 요청임을 검증할 수 있도록
        if (gatewayInternalSecret != null && !gatewayInternalSecret.isBlank()) {
            reqBuilder.header("X-Gateway-Secret", gatewayInternalSecret);
        }
        ServerHttpRequest mutatedRequest = reqBuilder.build();

        ServerWebExchange mutatedExchange = exchange.mutate()
                .request(mutatedRequest)
                .build();

        return chain.filter(mutatedExchange)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth));
    }

    private String extractToken(ServerHttpRequest request) {
        String bearerToken = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        // 쿼리 파라미터 토큰은 헤더를 못 붙이는 경로만 허용: WebSocket 연결, <img>가 쓰는 파일 GET
        // 디코딩된 세그먼트로 판단한다. "." / ".." 세그먼트("%2e%2e" 포함)가 있으면 /api/files/ 접두를 믿을 수 없다.
        StringBuilder sb = new StringBuilder();
        for (org.springframework.http.server.PathContainer.Element e : request.getPath().pathWithinApplication().elements()) {
            if (e instanceof org.springframework.http.server.PathContainer.PathSegment seg) {
                String v = seg.valueToMatch();
                if (v.equals(".") || v.equals("..")) {
                    return null;
                }
                if (!v.isEmpty()) {
                    sb.append('/').append(v);
                }
            }
        }
        String path = sb.toString();
        boolean ws = path.equals("/ws") || path.startsWith("/ws/")
                || path.equals("/ws-native") || path.startsWith("/ws-native/");
        boolean file = HttpMethod.GET.equals(request.getMethod()) && path.startsWith("/api/files/");
        if (!ws && !file) {
            return null;
        }
        return request.getQueryParams().getFirst("token");
    }
}
