package com.gossiplog.gateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

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

    /** INCR와 최초 EXPIRE를 한 번에 실행 — 둘 사이에서 끊겨 TTL 없는 키가 남는 일을 막는다. */
    public static final RedisScript<Long> INCR_EXPIRE_SCRIPT = RedisScript.of(
            "local c = redis.call('INCR', KEYS[1])\n" +
            "if c == 1 or redis.call('TTL', KEYS[1]) < 0 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end\n" +
            "return c",
            Long.class);

    private final ReactiveStringRedisTemplate redis;

    public LoginRateLimitFilter(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = canonicalPath(exchange);
        if (!path.equals("/api/auth/login") && !path.equals("/api/auth/register")) {
            return chain.filter(exchange);
        }

        String ip = ClientIpResolver.resolve(exchange.getRequest());
        String key = KEY_PREFIX + ip;

        return redis.execute(INCR_EXPIRE_SCRIPT, List.of(key), List.of(String.valueOf(WINDOW_SECONDS)))
                .next()
                .defaultIfEmpty(0L)
                .onErrorResume(e -> {
                    // Redis 장애 시 fail-open — 정상 서비스 유지 우선. 다운스트림 오류는 여기서 삼키면 안 된다
                    // (체인이 한 번 더 실행돼 가입 같은 요청이 두 번 처리된다).
                    log.warn("Login rate limit check failed (fail-open): {}", e.getMessage());
                    return Mono.just(0L);
                })
                .flatMap(count -> {
                    if (count > MAX_REQUESTS) {
                        log.warn("Login rate limit exceeded: ip={} path={} count={}", ip, path, count);
                        return writeRateLimited(exchange);
                    }
                    return chain.filter(exchange);
                });
    }

    /** 디코딩된 세그먼트로 정규화한다. "%6Cogin"이나 "//" 로 경로 비교를 우회하지 못하게 한다. */
    private static String canonicalPath(ServerWebExchange exchange) {
        StringBuilder sb = new StringBuilder();
        for (PathContainer.Element e : exchange.getRequest().getPath().pathWithinApplication().elements()) {
            if (e instanceof PathContainer.PathSegment seg && !seg.valueToMatch().isEmpty()) {
                sb.append('/').append(seg.valueToMatch());
            }
        }
        return sb.toString();
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
}
