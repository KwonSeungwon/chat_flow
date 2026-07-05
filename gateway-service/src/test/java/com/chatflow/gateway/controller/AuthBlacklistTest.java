package com.chatflow.gateway.controller;

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

    private String validToken;
    private String validJti;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        validToken = jwtUtil.generateToken("user-1", "testuser", "NURSE");
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
                .role("NURSE")
                .createdAt(LocalDateTime.now())
                .build();
        when(userRepository.findByUsername("testuser")).thenReturn(Mono.just(user));
        when(userRepository.save(any(UserEntity.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        // Redis template stubs for cacheUser + LoginRateLimitFilter
        ReactiveValueOperations<String, String> valueOps = mock(ReactiveValueOperations.class);
        lenient().when(reactiveStringRedisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(valueOps.set(anyString(), anyString(), any(Duration.class)))
                .thenReturn(Mono.just(true));
        // LoginRateLimitFilter calls increment() for /login and /register
        lenient().when(valueOps.increment(anyString())).thenReturn(Mono.just(1L));
        lenient().when(reactiveStringRedisTemplate.expire(anyString(), any(Duration.class)))
                .thenReturn(Mono.just(true));
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
                .expectStatus().isOk();

        // Confirm AuthService was actually invoked (user lookup happened)
        verify(userRepository).findByUsername("testuser");
    }

    // ── Login remains permitAll (not blocked by authenticated()) ──

    @Test
    void login_noToken_isPermitAll() {
        // Valid user with correct password → successful login (not blocked by Spring Security)
        String bcryptPassword = passwordEncoder.encode("pass1234");
        UserEntity loginUser = UserEntity.builder()
                .userId("user-login").username("loginuser")
                .encodedPassword(bcryptPassword).role("NURSE")
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
        when(userRepository.existsByUsername("newuser")).thenReturn(Mono.just(false));
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
}
