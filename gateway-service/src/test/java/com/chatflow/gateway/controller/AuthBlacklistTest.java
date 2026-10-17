package com.chatflow.gateway.controller;

import com.chatflow.gateway.config.LoginRateLimitFilter;
import com.chatflow.gateway.entity.UserEntity;
import com.chatflow.gateway.repository.UserRepository;
import com.chatflow.gateway.security.JwtUtil;
import com.chatflow.gateway.security.TokenBlacklistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration test: blacklisted JWT must not be able to change password or update profile.
 *
 * Uses the full reactive security chain (SecurityConfig + JwtAuthenticationWebFilter)
 * with a real JwtUtil and mocked TokenBlacklistService / UserRepository / Redis.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AuthBlacklistTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockBean
    private TokenBlacklistService tokenBlacklistService;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private ReactiveStringRedisTemplate reactiveStringRedisTemplate;

    @org.springframework.beans.factory.annotation.Value("${jwt.secret}")
    private String jwtSecret;

    private String validToken;
    private String validJti;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        validToken = jwtUtil.generateToken("user-1", "testuser");
        validJti = jwtUtil.getJti(validToken);

        // Default: token is NOT blacklisted
        when(tokenBlacklistService.isBlacklisted(anyString())).thenReturn(Mono.just(false));

        // User exists in DB with BCrypt-encoded password
        String bcryptPassword = passwordEncoder.encode("old12345");
        UserEntity user = UserEntity.builder()
                .seq(1L)
                .userId("user-1")
                .username("testuser")
                .encodedPassword(bcryptPassword)
                .createdAt(LocalDateTime.now())
                .build();
        when(userRepository.findByUsername("testuser")).thenReturn(Mono.just(user));
        when(userRepository.save(any(UserEntity.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(userRepository.updateEncodedPassword(anyString(), anyString())).thenReturn(Mono.just(1));
        lenient().when(userRepository.updateProfileImageUrl(anyString(), anyString())).thenReturn(Mono.just(1));
        lenient().when(tokenBlacklistService.blacklist(anyString(), any(Duration.class))).thenReturn(Mono.just(true));

        // Redis template stubs for LoginRateLimitFilter
        ReactiveValueOperations<String, String> valueOps = mock(ReactiveValueOperations.class);
        lenient().when(reactiveStringRedisTemplate.opsForValue()).thenReturn(valueOps);
        // LoginRateLimitFilter calls increment() for /login and /register
        lenient().when(valueOps.increment(anyString())).thenReturn(Mono.just(1L));
        // 가입 잠금(SET NX)과 해제
        lenient().when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(Mono.just(true));
        lenient().when(valueOps.delete(anyString())).thenReturn(Mono.just(true));
        lenient().when(reactiveStringRedisTemplate.expire(anyString(), any(Duration.class)))
                .thenReturn(Mono.just(true));
        // Lua scripts: rate-limit counters return 1, session rotation returns no previous jti
        lenient().when(reactiveStringRedisTemplate.execute(any(RedisScript.class), anyList(), anyList()))
                .thenAnswer(inv -> inv.getArgument(0) == LoginRateLimitFilter.INCR_EXPIRE_SCRIPT
                        ? Flux.just(1L) : Flux.empty());
    }

    // ── MUST-HAVE regression: blacklisted token → 401 on /api/auth/password ──

    @Test
    void changePassword_blacklistedToken_returns401() {
        when(tokenBlacklistService.isBlacklisted(validJti)).thenReturn(Mono.just(true));

        webTestClient.put().uri("/api/auth/password")
                .header("Authorization", "Bearer " + validToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"currentPassword\":\"old12345\",\"newPassword\":\"newpass12345\"}")
                .exchange()
                .expectStatus().isUnauthorized();

        // AuthService.changePassword must NEVER be invoked
        verify(userRepository, never()).findByUsername(anyString());
    }

    // ── Blacklisted token → 401 on /api/auth/profile ──

    @Test
    void updateProfile_blacklistedToken_returns401() {
        when(tokenBlacklistService.isBlacklisted(validJti)).thenReturn(Mono.just(true));

        webTestClient.put().uri("/api/auth/profile")
                .header("Authorization", "Bearer " + validToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"profileImageUrl\":\"https://cdn/evil.png\"}")
                .exchange()
                .expectStatus().isUnauthorized();

        verify(userRepository, never()).findByUsername(anyString());
    }

    // ── Malformed token → 401 (not 500) on /api/auth/password ──

    @Test
    void changePassword_malformedToken_returns401() {
        webTestClient.put().uri("/api/auth/password")
                .header("Authorization", "Bearer not-a-real-jwt")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"currentPassword\":\"old12345\",\"newPassword\":\"newpass12345\"}")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ── Missing Authorization header → 401 on /api/auth/password ──

    @Test
    void changePassword_noToken_returns401() {
        webTestClient.put().uri("/api/auth/password")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"currentPassword\":\"old12345\",\"newPassword\":\"newpass12345\"}")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ── Valid token → password change proceeds (reaches AuthService, not 401) ──

    @Test
    void changePassword_validToken_proceeds() {
        when(tokenBlacklistService.isBlacklisted(validJti)).thenReturn(Mono.just(false));

        webTestClient.put().uri("/api/auth/password")
                .header("Authorization", "Bearer " + validToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"currentPassword\":\"old12345\",\"newPassword\":\"newpass12345\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.message").isEqualTo("비밀번호가 변경되었습니다.")
                .jsonPath("$.token").isNotEmpty();

        // Confirm AuthService was actually invoked (user lookup happened)
        verify(userRepository).findByUsername("testuser");
        verify(tokenBlacklistService).blacklist(eq(validJti), any(Duration.class));
    }

    // ── Login remains permitAll (not blocked by authenticated()) ──

    @Test
    void login_noToken_isPermitAll() {
        // Valid user with correct password → successful login (not blocked by Spring Security)
        String bcryptPassword = passwordEncoder.encode("pass1234");
        UserEntity loginUser = UserEntity.builder()
                .userId("user-login").username("loginuser")
                .encodedPassword(bcryptPassword)
                .createdAt(LocalDateTime.now())
                .build();
        when(userRepository.findByUsername("loginuser")).thenReturn(Mono.just(loginUser));
        when(reactiveStringRedisTemplate.execute(any(RedisScript.class), anyList(), anyList()))
                .thenReturn(Flux.empty());

        webTestClient.post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"username\":\"loginuser\",\"password\":\"pass1234\"}")
                .exchange()
                // 200 from AuthController.login — NOT 401 from Spring Security gate
                .expectStatus().isOk();
    }

    // ── Register remains permitAll ──

    @Test
    @SuppressWarnings("unchecked")
    void register_noToken_isPermitAll() {
        when(userRepository.existsByUsernameIgnoreCase("newuser")).thenReturn(Mono.just(false));
        when(userRepository.save(any(UserEntity.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(reactiveStringRedisTemplate.execute(any(RedisScript.class), anyList(), anyList()))
                .thenReturn(Flux.empty());

        webTestClient.post().uri("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"username\":\"newuser\",\"password\":\"securepass123\"}")
                .exchange()
                // Should succeed (200), NOT 401 from Spring Security
                .expectStatus().isOk();
    }

    // ── Prometheus scrape: 인증 없이 통과 (401이 아님; 테스트 프로필은 health만 노출하므로 404) ──

    @Test
    void actuatorPrometheus_isPermitAll() {
        webTestClient.get().uri("/actuator/prometheus").exchange().expectStatus().isNotFound();
    }

    @Test
    void actuatorOther_stillRequiresAuth() {
        webTestClient.get().uri("/actuator/env").exchange().expectStatus().isUnauthorized();
    }

    // ── Logout remains permitAll (even with a blacklisted token) ──

    @Test
    void logout_blacklistedToken_isPermitAll() {
        // Logout must work even if the token is about to be / already blacklisted
        when(tokenBlacklistService.isBlacklisted(validJti)).thenReturn(Mono.just(true));

        // Logout's internal logic: jwtUtil is a real bean
        when(tokenBlacklistService.blacklist(anyString(), any(Duration.class)))
                .thenReturn(Mono.just(true));
        when(reactiveStringRedisTemplate.delete(anyString())).thenReturn(Mono.just(1L));

        webTestClient.post().uri("/api/auth/logout")
                .header("Authorization", "Bearer " + validToken)
                .exchange()
                .expectStatus().isOk();
    }

    // ── M4: profileImageUrl validation ──

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec putProfile(String url) {
        return webTestClient.put().uri("/api/auth/profile")
                .header("Authorization", "Bearer " + validToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"username\":\"testuser\",\"profileImageUrl\":\"" + url + "\"}")
                .exchange();
    }

    @Test
    void updateProfile_uploadFlowValue_accepted() {
        putProfile("/api/files/3f2b8c1e-aaaa-bbbb-cccc-1234567890ab").expectStatus().isOk();
        putProfile("https://cdn.example.com/a.png").expectStatus().isOk();
    }

    @Test
    void updateProfile_protocolRelativeHttpAndOtherPaths_rejected() {
        putProfile("//evil.example.com/a.png").expectStatus().isBadRequest();
        putProfile("http://cdn.example.com/a.png").expectStatus().isBadRequest();
        putProfile("javascript:alert(1)").expectStatus().isBadRequest();
        putProfile("/etc/passwd").expectStatus().isBadRequest();
    }

    // ── L6: expired / malformed token logout is an idempotent no-op (200), never 500 ──

    @Test
    void logout_expiredToken_isIdempotentOk() {
        String expired = io.jsonwebtoken.Jwts.builder()
                .id("exp-jti").subject("user-1").claim("username", "testuser")
                .issuedAt(new java.util.Date(System.currentTimeMillis() - 7_200_000))
                .expiration(new java.util.Date(System.currentTimeMillis() - 3_600_000))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        jwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .compact();

        webTestClient.post().uri("/api/auth/logout")
                .header("Authorization", "Bearer " + expired)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void logout_malformedToken_isIdempotentOk() {
        webTestClient.post().uri("/api/auth/logout")
                .header("Authorization", "Bearer garbage")
                .exchange()
                .expectStatus().isOk();
    }

    // ── L10: ?token= only for websocket and file GETs ──

    @Test
    void queryParamToken_onRegularApiPath_isIgnored() {
        webTestClient.get().uri("/api/users/me?token=" + validToken)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void queryParamToken_onFileGet_stillAuthenticates() {
        webTestClient.get().uri("/api/files/3f2b8c1e-aaaa-4bbb-8ccc-1234567890ab?token=" + validToken)
                .exchange()
                .expectStatus().value(status -> org.junit.jupiter.api.Assertions.assertNotEquals(401, status));
    }

    @Test
    void queryParamToken_onFilePathWithDotSegments_isIgnored() {
        for (String p : new String[] {"/api/files/%2e%2e/users/me", "/api/files/%2E%2E/users/me", "/api/files/./../users/me",
                "/api/files/..%2fusers/me", "/api/files/..%2Fusers/me", "/api/files/abc/extra", "/api/files/a%2Fb", "/api/files/a%5Cb"}) {
            webTestClient.get().uri(uriBuilder -> uriBuilder.path("/").build().resolve(p + "?token=" + validToken))
                    .exchange()
                    .expectStatus().isUnauthorized();
        }
    }

    // ── M3: register error body and duplicate handling ──

    @Test
    void register_invalidUsername_returns400WithMessage() {
        webTestClient.post().uri("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"username\":\"bad name!\",\"password\":\"securepass123\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isNotEmpty();
    }

    @Test
    void register_duplicateRace_returns409() {
        when(userRepository.existsByUsernameIgnoreCase("newuser")).thenReturn(Mono.just(false));
        doReturn(Mono.error(new org.springframework.dao.DataIntegrityViolationException("dup")))
                .when(userRepository).save(any(UserEntity.class));

        webTestClient.post().uri("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"username\":\"newuser\",\"password\":\"securepass123\"}")
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.error").isEqualTo("이미 사용 중인 아이디입니다.");
    }

    @Test
    @SuppressWarnings("unchecked")
    void register_lockHeld_returns409WithBody() {
        ReactiveValueOperations<String, String> ops = reactiveStringRedisTemplate.opsForValue();
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(Mono.just(false));

        webTestClient.post().uri("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"username\":\"newuser\",\"password\":\"securepass123\"}")
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.error")
                .isEqualTo("같은 아이디로 가입이 진행 중입니다. 잠시 후 다시 시도해 주세요.");
    }

    // ── M5: password change attempts limited per user ──

    @Test
    void changePassword_sixthAttempt_returns429() {
        when(reactiveStringRedisTemplate.execute(eq(LoginRateLimitFilter.INCR_EXPIRE_SCRIPT), anyList(), anyList()))
                .thenReturn(Flux.just(6L));

        webTestClient.put().uri("/api/auth/password")
                .header("Authorization", "Bearer " + validToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"currentPassword\":\"old12345\",\"newPassword\":\"newpass12345\"}")
                .exchange()
                .expectStatus().isEqualTo(429);
    }
}
