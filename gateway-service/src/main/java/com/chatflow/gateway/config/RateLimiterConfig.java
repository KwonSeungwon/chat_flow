package com.chatflow.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 레이트 리밋 KeyResolver 정의.
 *
 * - ipKeyResolver: IP 기반 (로그인/회원가입 등 미인증 엔드포인트)
 * - userKeyResolver: X-User-Id 헤더 기반 (인증된 API)
 *   - 미인증 요청은 IP로 폴백 (X-User-Id가 없어도 레이트 리밋 우회 불가)
 */
@Configuration
public class RateLimiterConfig {

    @Bean
    public KeyResolver ipKeyResolver() {
        return exchange -> Mono.just("ip:" + resolveClientIp(exchange));
    }

    /**
     * 대부분의 인증된 라우트에서 기본으로 사용 — @Primary로 Spring Cloud Gateway의
     * 기본 KeyResolver 주입을 만족시킨다 (두 개 빈이 있을 때 충돌 방지).
     */
    @Bean
    @Primary
    public KeyResolver userKeyResolver() {
        return exchange -> {
            String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
            if (userId != null && !userId.isBlank()) {
                return Mono.just("user:" + userId);
            }
            // 미인증 요청은 IP로 폴백 — 익명 요청의 무제한 남용 차단
            return Mono.just("ip:" + resolveClientIp(exchange));
        };
    }

    private static String resolveClientIp(ServerWebExchange exchange) {
        String xff = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return exchange.getRequest().getRemoteAddress() != null
                ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                : "unknown";
    }
}
