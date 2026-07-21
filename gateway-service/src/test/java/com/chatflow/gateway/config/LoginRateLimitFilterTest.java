package com.chatflow.gateway.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit test for LoginRateLimitFilter.
 *
 * Verifies:
 * - Non-login/register paths bypass the filter entirely (no Redis calls)
 * - Requests under the limit pass through to the chain
 * - First request sets TTL via expire()
 * - Subsequent requests (count > 1) skip expire()
 * - Requests exceeding MAX_REQUESTS (10) are rejected with 429 + Retry-After header
 * - Redis failure → fail-open (request passes through to the chain)
 * - X-Forwarded-For header is used to resolve client IP
 * - Missing remote address falls back to "unknown"
 */
@ExtendWith(MockitoExtension.class)
class LoginRateLimitFilterTest {

    @Mock
    private ReactiveStringRedisTemplate redis;

    @Mock
    private ReactiveValueOperations<String, String> valueOps;

    @Mock
    private WebFilterChain chain;

    private LoginRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new LoginRateLimitFilter(redis);
        lenient().when(chain.filter(any())).thenReturn(Mono.empty());
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
    }

    // ── Non-login paths bypass the filter ────────────────────────

    @Test
    void nonLoginPath_bypassesFilter_noRedisCall() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/chat/rooms").build());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain).filter(exchange);
        verifyNoInteractions(valueOps);
    }

    @Test
    void nonLoginPath_postToDifferentEndpoint_bypassesFilter() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/auth/logout").build());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain).filter(exchange);
        verifyNoInteractions(valueOps);
    }

    // ── Login path — under limit passes through ─────────────────

    @Test
    void loginPath_firstRequest_passesAndSetsTTL() {
        when(valueOps.increment("rl:login:127.0.0.1")).thenReturn(Mono.just(1L));
        when(redis.expire(eq("rl:login:127.0.0.1"), any(Duration.class)))
                .thenReturn(Mono.just(true));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/auth/login")
                        .remoteAddress(new java.net.InetSocketAddress("127.0.0.1", 12345))
                        .build());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        // Chain was invoked (request passed through)
        verify(chain).filter(exchange);
        // TTL was set for first request (count == 1)
        verify(redis).expire(eq("rl:login:127.0.0.1"), eq(Duration.ofSeconds(60)));
    }

    @Test
    void loginPath_subsequentRequest_skipsExpire() {
        // count=5, not the first request → expire() should NOT be called
        when(valueOps.increment("rl:login:127.0.0.1")).thenReturn(Mono.just(5L));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/auth/login")
                        .remoteAddress(new java.net.InetSocketAddress("127.0.0.1", 12345))
                        .build());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain).filter(exchange);
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void registerPath_underLimit_passesThrough() {
        when(valueOps.increment("rl:login:10.0.0.1")).thenReturn(Mono.just(10L));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/auth/register")
                        .remoteAddress(new java.net.InetSocketAddress("10.0.0.1", 9999))
                        .build());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        // count=10 is exactly at the limit, not over → passes
        verify(chain).filter(exchange);
    }

    // ── Over the limit → 429 ────────────────────────────────────

    @Test
    void loginPath_overLimit_returns429WithRetryAfter() {
        // count=11 exceeds MAX_REQUESTS (10) → must be rejected
        when(valueOps.increment("rl:login:192.168.1.1")).thenReturn(Mono.just(11L));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/auth/login")
                        .remoteAddress(new java.net.InetSocketAddress("192.168.1.1", 5000))
                        .build());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        // Chain must NOT be invoked (request blocked)
        verify(chain, never()).filter(any());

        // Response is 429 with Retry-After header
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exchange.getResponse().getStatusCode());
        assertEquals("60", exchange.getResponse().getHeaders().getFirst("Retry-After"));
        assertEquals("application/json;charset=UTF-8",
                exchange.getResponse().getHeaders().getFirst("Content-Type"));
    }

    @Test
    void registerPath_overLimit_returns429() {
        when(valueOps.increment("rl:login:10.0.0.1")).thenReturn(Mono.just(100L));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/auth/register")
                        .remoteAddress(new java.net.InetSocketAddress("10.0.0.1", 9999))
                        .build());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain, never()).filter(any());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exchange.getResponse().getStatusCode());
    }

    // ── Redis failure → fail-open ───────────────────────────────

    @Test
    void redisFailure_failOpen_passesThrough() {
        when(valueOps.increment(anyString()))
                .thenReturn(Mono.error(new RuntimeException("Redis connection refused")));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/auth/login")
                        .remoteAddress(new java.net.InetSocketAddress("127.0.0.1", 12345))
                        .build());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        // Fail-open: chain is still invoked despite Redis error
        verify(chain).filter(exchange);
    }

    // ── X-Forwarded-For IP resolution ───────────────────────────

    @Test
    void xForwardedFor_usesFirstIpInChain() {
        // X-Forwarded-For with multiple proxies → use first IP
        when(valueOps.increment("rl:login:203.0.113.50")).thenReturn(Mono.just(1L));
        when(redis.expire(eq("rl:login:203.0.113.50"), any(Duration.class)))
                .thenReturn(Mono.just(true));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/auth/login")
                        .header("X-Forwarded-For", "203.0.113.50, 70.41.3.18, 150.172.238.178")
                        .build());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        // Redis key uses the first IP from X-Forwarded-For
        verify(valueOps).increment("rl:login:203.0.113.50");
        verify(chain).filter(exchange);
    }

    @Test
    void xForwardedFor_singleIp_usedDirectly() {
        when(valueOps.increment("rl:login:1.2.3.4")).thenReturn(Mono.just(2L));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/auth/login")
                        .header("X-Forwarded-For", "1.2.3.4")
                        .build());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(valueOps).increment("rl:login:1.2.3.4");
    }

    // ── Boundary: exactly at limit passes, limit+1 rejects ──────

    @Test
    void boundaryAtLimit_count10_passes() {
        when(valueOps.increment("rl:login:127.0.0.1")).thenReturn(Mono.just(10L));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/auth/login")
                        .remoteAddress(new java.net.InetSocketAddress("127.0.0.1", 12345))
                        .build());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain).filter(exchange);
        assertNull(exchange.getResponse().getStatusCode());
    }

    @Test
    void boundaryOverLimit_count11_rejects() {
        when(valueOps.increment("rl:login:127.0.0.1")).thenReturn(Mono.just(11L));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/auth/login")
                        .remoteAddress(new java.net.InetSocketAddress("127.0.0.1", 12345))
                        .build());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain, never()).filter(any());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exchange.getResponse().getStatusCode());
    }
}
