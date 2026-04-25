package com.chatflow.chat.config;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JwtAuthFilterTest {

    @Mock private JwtUtil jwtUtil;
    @Mock private TokenBlacklistService tokenBlacklistService;
    @Mock private FilterChain filterChain;

    @InjectMocks
    private JwtAuthFilter jwtAuthFilter;

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        ReflectionTestUtils.setField(jwtAuthFilter, "gatewayInternalSecret", "test-secret-value");
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── 1. Valid token sets authentication ───────────────────────

    @Test
    void validToken_setsAuthentication() throws Exception {
        // given
        request.addHeader("Authorization", "Bearer valid-token");
        request.setRequestURI("/api/chat/rooms");

        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("user-123");
        when(claims.getId()).thenReturn("jti-abc");
        when(jwtUtil.parseToken("valid-token")).thenReturn(claims);
        when(tokenBlacklistService.isBlacklisted("jti-abc")).thenReturn(false);

        // when
        jwtAuthFilter.doFilterInternal(request, response, filterChain);

        // then
        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth, "Authentication should be set");
        assertEquals("user-123", auth.getPrincipal());
        verify(filterChain).doFilter(request, response);
    }

    // ── 2. Blacklisted token rejected ────────────────────────────

    @Test
    void blacklistedTokenRejected() throws Exception {
        // given
        request.addHeader("Authorization", "Bearer blacklisted-token");
        request.setRequestURI("/api/chat/rooms");

        Claims claims = mock(Claims.class);
        lenient().when(claims.getSubject()).thenReturn("user-456");
        when(claims.getId()).thenReturn("jti-bad");
        when(jwtUtil.parseToken("blacklisted-token")).thenReturn(claims);
        when(tokenBlacklistService.isBlacklisted("jti-bad")).thenReturn(true);

        // when
        jwtAuthFilter.doFilterInternal(request, response, filterChain);

        // then
        assertNull(SecurityContextHolder.getContext().getAuthentication(),
                "Authentication should NOT be set for blacklisted token");
        verify(filterChain).doFilter(request, response);
    }

    // ── 3. No token, no authentication ───────────────────────────

    @Test
    void noToken_noAuthentication() throws Exception {
        // given: no Authorization header, non-WS path
        request.setRequestURI("/api/chat/rooms");

        // when
        jwtAuthFilter.doFilterInternal(request, response, filterChain);

        // then
        assertNull(SecurityContextHolder.getContext().getAuthentication(),
                "No token means no authentication");
        verify(filterChain).doFilter(request, response);
    }

    // ── 4. X-User-Id header with valid gateway secret ────────────

    @Test
    void xUserIdHeader_skipsBlacklist() throws Exception {
        // given: Gateway-injected headers
        request.addHeader("X-User-Id", "gw-user-789");
        request.addHeader("X-Username", "gatewayuser");
        request.addHeader("X-Gateway-Secret", "test-secret-value");

        // when
        jwtAuthFilter.doFilterInternal(request, response, filterChain);

        // then
        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth, "Authentication should be set via gateway trust path");
        assertEquals("gw-user-789", auth.getPrincipal());
        // Token parsing and blacklist check should NOT be called
        verify(jwtUtil, never()).parseToken(anyString());
        verify(tokenBlacklistService, never()).isBlacklisted(anyString());
        // filterChain.doFilter is called with a wrapped request (not the original)
        verify(filterChain).doFilter(any(), eq(response));
    }

    // ── 5. Invalid token clears context, no exception ────────────

    @Test
    void invalidToken_clearedContext_noException() throws Exception {
        // given
        request.addHeader("Authorization", "Bearer bad-token");
        request.setRequestURI("/api/chat/rooms");

        when(jwtUtil.parseToken("bad-token")).thenThrow(new RuntimeException("Invalid JWT"));

        // when -- should not throw
        assertDoesNotThrow(() ->
                jwtAuthFilter.doFilterInternal(request, response, filterChain));

        // then
        assertNull(SecurityContextHolder.getContext().getAuthentication(),
                "Context should be cleared on parse failure");
        verify(filterChain).doFilter(request, response);
    }
}
