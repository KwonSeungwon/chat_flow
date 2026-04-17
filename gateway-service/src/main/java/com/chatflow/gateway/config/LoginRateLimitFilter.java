package com.chatflow.gateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 로그인/회원가입 경로 전용 IP 기반 레이트 리밋 (Fixed Window, Redis 기반).
 *
 * 게이트웨이 자체 컨트롤러(/api/auth/**)는 Spring Cloud Gateway 라우트 필터가 적용되지 않으므로
 * 별도의 WebFilter로 브루트포스를 차단한다.
 *
 * 제한: IP당 60초 윈도우 내 최대 10회 — 로그인/회원가입만 적용.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class LoginRateLimitFilter implements WebFilter {

    private static final String KEY_PREFIX = "rl:login:";
    private static final int WINDOW_SECONDS = 60;
    private static final int MAX_REQUESTS = 10;

    private final ReactiveStringRedisTemplate redis;

    public LoginRateLimitFilter(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!path.equals("/api/auth/login") && !path.equals("/api/auth/register")) {
            return chain.filter(exchange);
        }

        String ip = resolveClientIp(exchange);
        String key = KEY_PREFIX + ip;

        return redis.opsForValue().increment(key)
                .flatMap(count -> {
                    // 첫 요청일 때만 TTL 설정
                    Mono<Boolean> expire = (count != null && count == 1L)
                            ? redis.expire(key, Duration.ofSeconds(WINDOW_SECONDS))
                            : Mono.just(true);
                    return expire.thenReturn(count != null ? count : 0L);
                })
                .flatMap(count -> {
                    if (count > MAX_REQUESTS) {
                        log.warn("Login rate limit exceeded: ip={} path={} count={}", ip, path, count);
                        return writeRateLimited(exchange);
                    }
                    return chain.filter(exchange);
                })
                .onErrorResume(e -> {
                    // Redis 장애 시 fail-open — 정상 서비스 유지 우선
                    log.warn("Login rate limit check failed (fail-open): {}", e.getMessage());
                    return chain.filter(exchange);
                });
    }

    private Mono<Void> writeRateLimited(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().add("Retry-After", String.valueOf(WINDOW_SECONDS));
        exchange.getResponse().getHeaders().add("Content-Type", "application/json;charset=UTF-8");
        String body = "{\"status\":429,\"code\":\"TOO_MANY_REQUESTS\",\"message\":\"너무 많은 로그인 시도입니다. "
                + WINDOW_SECONDS + "초 후 다시 시도해주세요.\"}";
        var buffer = exchange.getResponse().bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
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
