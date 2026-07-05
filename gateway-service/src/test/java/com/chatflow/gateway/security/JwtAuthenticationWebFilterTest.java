package com.chatflow.gateway.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit test for JwtAuthenticationWebFilter.
 *
 * Verifies:
 * - JWT is parsed exactly ONCE per request (via tryParse, not double parse)
 * - Valid token → authenticates and injects X-User-Id, X-Username, X-Gateway-Secret headers
 * - Blacklisted token → passes through unauthenticated (Security returns 401)
 * - Invalid/malformed token → passes through unauthenticated
 * - No token → passes through unauthenticated
 * - Legacy token (no JTI) → authenticates without blacklist check
 * - Client-injected X-User-Id/X-Username headers are stripped
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthenticationWebFilterTest {

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private TokenBlacklistService tokenBlacklistService;

    @Mock
    private WebFilterChain chain;

    private JwtAuthenticationWebFilter filter;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationWebFilter(jwtUtil, tokenBlacklistService);
        // Inject the gateway internal secret via reflection (set by @Value in production)
        try {
            var field = JwtAuthenticationWebFilter.class.getDeclaredField("gatewayInternalSecret");
            field.setAccessible(true);
            field.set(filter, "test-gateway-secret");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        lenient().when(chain.filter(any())).thenReturn(Mono.empty());
    }

    // ── Single-parse verification ────────────────────────────────

    @Test
    void validToken_parsesExactlyOnce() {
        // given
        Claims claims = buildClaims("user-1", "testuser", "jti-123");
        when(jwtUtil.tryParse("valid-token")).thenReturn(Optional.of(claims));
        when(tokenBlacklistService.isBlacklisted("jti-123")).thenReturn(Mono.just(false));

        MockServerWebExchange exchange = exchangeWithToken("valid-token");

        // when
        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        // then: tryParse called once, parseToken never called directly
        verify(jwtUtil, times(1)).tryParse("valid-token");
        verify(jwtUtil, never()).parseToken(anyString());
    }

    // ── Valid token → authenticated + headers injected ───────────

    @Test
    void validToken_injectsHeaders() {
        Claims claims = buildClaims("user-42", "alice", "jti-abc");
        when(jwtUtil.tryParse("tok")).thenReturn(Optional.of(claims));
        when(tokenBlacklistService.isBlacklisted("jti-abc")).thenReturn(Mono.just(false));

        MockServerWebExchange exchange = exchangeWithToken("tok");

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        // Verify chain was called with mutated exchange containing injected headers
        verify(chain).filter(argThat(ex -> {
            var headers = ex.getRequest().getHeaders();
            return "user-42".equals(headers.getFirst("X-User-Id"))
                    && headers.getFirst("X-Username") != null
                    && "test-gateway-secret".equals(headers.getFirst("X-Gateway-Secret"));
        }));
    }

    // ── Blacklisted token → unauthenticated (no headers) ────────

    @Test
    void blacklistedToken_passesUnauthenticated() {
        Claims claims = buildClaims("user-1", "bob", "jti-blocked");
        when(jwtUtil.tryParse("blocked-tok")).thenReturn(Optional.of(claims));
        when(tokenBlacklistService.isBlacklisted("jti-blocked")).thenReturn(Mono.just(true));

        MockServerWebExchange exchange = exchangeWithToken("blocked-tok");

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        // chain.filter called with the sanitized exchange (no X-User-Id injected)
        verify(chain).filter(argThat(ex -> {
            var headers = ex.getRequest().getHeaders();
            return headers.getFirst("X-User-Id") == null;
        }));
    }

    // ── Invalid/malformed token → unauthenticated ────────────────

    @Test
    void invalidToken_passesUnauthenticated() {
        when(jwtUtil.tryParse("bad-token")).thenReturn(Optional.empty());

        MockServerWebExchange exchange = exchangeWithToken("bad-token");

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(jwtUtil, times(1)).tryParse("bad-token");
        verify(tokenBlacklistService, never()).isBlacklisted(anyString());
        verify(chain).filter(argThat(ex -> {
            var headers = ex.getRequest().getHeaders();
            return headers.getFirst("X-User-Id") == null;
        }));
    }

    // ── No token → unauthenticated, no parse attempted ───────────

    @Test
    void noToken_passesUnauthenticated_noParse() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/chat/rooms").build());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(jwtUtil, never()).tryParse(anyString());
        verify(jwtUtil, never()).parseToken(anyString());
    }

    // ── Legacy token (no JTI) → authenticates without blacklist ──

    @Test
    void legacyToken_noJti_authenticatesWithoutBlacklistCheck() {
        Claims claims = buildClaims("user-legacy", "legacy-user", null);
        when(jwtUtil.tryParse("legacy-tok")).thenReturn(Optional.of(claims));

        MockServerWebExchange exchange = exchangeWithToken("legacy-tok");

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        // Blacklist never checked (JTI is null)
        verify(tokenBlacklistService, never()).isBlacklisted(anyString());

        // But headers are still injected (user is authenticated)
        verify(chain).filter(argThat(ex -> {
            var headers = ex.getRequest().getHeaders();
            return "user-legacy".equals(headers.getFirst("X-User-Id"));
        }));
    }

    // ── Header sanitization: client X-User-Id/X-Username stripped ─

    @Test
    void clientInjectedHeaders_areStripped() {
        // Client tries to inject X-User-Id and X-Username directly
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/chat/rooms")
                        .header("X-User-Id", "evil-user")
                        .header("X-Username", "hacker")
                        .build());

        // No valid token → should pass through without those headers
        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain).filter(argThat(ex -> {
            var headers = ex.getRequest().getHeaders();
            return headers.getFirst("X-User-Id") == null
                    && headers.getFirst("X-Username") == null;
        }));
    }

    // ── WebSocket query param token extraction ───────────────────

    @Test
    void queryParamToken_parsedAndAuthenticated() {
        Claims claims = buildClaims("ws-user", "wsname", "ws-jti");
        when(jwtUtil.tryParse("ws-token")).thenReturn(Optional.of(claims));
        when(tokenBlacklistService.isBlacklisted("ws-jti")).thenReturn(Mono.just(false));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/ws?token=ws-token").build());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(jwtUtil, times(1)).tryParse("ws-token");
        verify(chain).filter(argThat(ex ->
                "ws-user".equals(ex.getRequest().getHeaders().getFirst("X-User-Id"))));
    }

    // ── Helpers ──────────────────────────────────────────────────

    private MockServerWebExchange exchangeWithToken(String token) {
        return MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/chat/rooms")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build());
    }

    private Claims buildClaims(String userId, String username, String jti) {
        Claims claims = mock(Claims.class);
        lenient().when(claims.getSubject()).thenReturn(userId);
        lenient().when(claims.get("username", String.class)).thenReturn(username);
        lenient().when(claims.getId()).thenReturn(jti);
        return claims;
    }
}
