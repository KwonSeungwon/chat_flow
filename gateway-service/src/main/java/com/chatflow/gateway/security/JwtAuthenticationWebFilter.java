package com.chatflow.gateway.security;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationWebFilter implements WebFilter {

    private final JwtUtil jwtUtil;
    private final TokenBlacklistService tokenBlacklistService;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String token = extractToken(exchange.getRequest());

        if (token == null || !jwtUtil.isValid(token)) {
            return chain.filter(exchange);
        }

        Claims claims = jwtUtil.parseToken(token);
        String jti = claims.getId();

        // JTI가 없는 레거시 토큰은 서명 검증만으로 통과
        if (jti == null) {
            return authenticateAndContinue(exchange, chain, claims);
        }

        // Redis 블랙리스트 확인 (리액티브)
        return tokenBlacklistService.isBlacklisted(jti)
                .flatMap(blacklisted -> {
                    if (blacklisted) {
                        log.debug("블랙리스트 토큰 거부: jti={}", jti);
                        return chain.filter(exchange); // 인증 없이 통과 → Security가 401 반환
                    }
                    return authenticateAndContinue(exchange, chain, claims);
                });
    }

    private Mono<Void> authenticateAndContinue(ServerWebExchange exchange, WebFilterChain chain, Claims claims) {
        String userId = claims.getSubject();
        String username = claims.get("username", String.class);

        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(userId, null, List.of());

        // 다운스트림 서비스에 사용자 정보 헤더 주입
        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .header("X-User-Id", userId)
                .header("X-Username", username)
                .build();

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
        // WebSocket 연결용 쿼리 파라미터 지원
        return request.getQueryParams().getFirst("token");
    }
}
