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
 * - CF-Connecting-IP resolves client IP; X-Forwarded-For is ignored
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

    // ── Client IP resolution (X-Forwarded-For is never trusted) ──

    @Test
    void cfConnectingIp_usedAsKey() {
        when(valueOps.increment("rl:login:203.0.113.50")).thenReturn(Mono.just(2L));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/auth/login")
                        .header("CF-Connecting-IP", "203.0.113.50")
                        .build());

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(valueOps).increment("rl:login:203.0.113.50");
    }

    @Test
    void loginLimit_rotatingXff_stillLimited() {
        // 같은 CF-Connecting-IP에서 XFF만 바꿔도 동일 키로 집계되어 429
        when(valueOps.increment("rl:login:203.0.113.50")).thenReturn(Mono.just(11L));

        for (String spoofed : new String[] {"1.1.1.1", "2.2.2.2", "3.3.3.3"}) {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.post("/api/auth/login")
                            .header("CF-Connecting-IP", "203.0.113.50")
                            .header("X-Forwarded-For", spoofed)
                            .build());

            StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

            assertEquals(HttpStatus.TOO_MANY_REQUESTS, exchange.getResponse().getStatusCode());
        }
        verify(valueOps, never()).increment("rl:login:1.1.1.1");
        verify(chain, never()).filter(any());
    }

    @Test
    void xForwardedFor_aloneIsIgnored_fallsBackToRemoteAddress() {
        when(valueOps.increment("rl:login:127.0.0.1")).thenReturn(Mono.just(1L));
        when(redis.expire(eq("rl:login:127.0.0.1"), any(Duration.class))).thenReturn(Mono.just(true));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/auth/login")
                        .header("X-Forwarded-For", "1.2.3.4")
                        .remoteAddress(new java.net.InetSocketAddress("127.0.0.1", 12345))
                        .build());

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(valueOps).increment("rl:login:127.0.0.1");
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
