package com.chatflow.gateway.security;

import com.chatflow.gateway.entity.UserEntity;
import com.chatflow.gateway.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.security.crypto.password.PasswordEncoder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private JwtUtil jwtUtil;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private TokenBlacklistService tokenBlacklistService;
    @Mock private ReactiveStringRedisTemplate redisTemplate;
    @Mock private ReactiveValueOperations<String, String> valueOps;
    @Mock private ObjectMapper objectMapper;
    @Mock private UserRepository userRepository;

    private AuthService authService;

    private static final long EXPIRATION_MS = 3_600_000L;

    @BeforeEach
    void setUp() {
        authService = new AuthService(
                jwtUtil, passwordEncoder, tokenBlacklistService,
                redisTemplate, objectMapper, userRepository, EXPIRATION_MS);
    }

    // ── login ────────────────────────────────────────────────────

    @Test
    void login_invalidatesPreviousJti() {
        // given: user exists with valid password
        UserEntity entity = UserEntity.builder()
                .userId("user-1").username("user1")
                .encodedPassword("encoded").role("NURSE")
                .profileImageUrl(null).createdAt(LocalDateTime.now())
                .build();

        lenient().when(userRepository.findByUsername("user1")).thenReturn(Mono.just(entity));
        when(passwordEncoder.matches("pass1234", "encoded")).thenReturn(true);
        when(jwtUtil.generateToken("user-1", "user1", "NURSE")).thenReturn("new-token");
        when(jwtUtil.getJti("new-token")).thenReturn("new-jti-123");

        // Lua script execution (rotateActiveJti) — returns prev jti or empty
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyList()))
                .thenReturn(Flux.just("old-jti-prev"));

        // cacheUser
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        try {
            lenient().when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (Exception ignored) {}
        lenient().when(valueOps.set(anyString(), anyString(), any(Duration.class)))
                .thenReturn(Mono.just(true));

        // when
        var req = new AuthService.AuthRequest("user1", "pass1234", null);

        StepVerifier.create(authService.login(req))
                .assertNext(resp -> {
                    assertNotNull(resp.token());
                    assertEquals("user1", resp.username());
                    assertEquals("user-1", resp.userId());
                })
                .verifyComplete();

        // then: Lua script was invoked (atomic rotate)
        verify(redisTemplate).execute(any(RedisScript.class), anyList(), anyList());
    }

    @Test
    void login_firstTime_noBlacklist() {
        // given: user with no prior session
        UserEntity entity = UserEntity.builder()
                .userId("user-2").username("newuser")
                .encodedPassword("enc").role("DOCTOR")
                .createdAt(LocalDateTime.now())
                .build();

        when(userRepository.findByUsername("newuser")).thenReturn(Mono.just(entity));
        when(passwordEncoder.matches("password", "enc")).thenReturn(true);
        when(jwtUtil.generateToken("user-2", "newuser", "DOCTOR")).thenReturn("token-abc");
        when(jwtUtil.getJti("token-abc")).thenReturn("jti-first");

        // Lua script handles both first-time and rotation atomically — empty = no prev session
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyList()))
                .thenReturn(Flux.empty());

        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        try {
            lenient().when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (Exception ignored) {}
        lenient().when(valueOps.set(anyString(), anyString(), any(Duration.class)))
                .thenReturn(Mono.just(true));

        // when
        var req = new AuthService.AuthRequest("newuser", "password", "DOCTOR");

        StepVerifier.create(authService.login(req))
                .assertNext(resp -> assertEquals("newuser", resp.username()))
                .verifyComplete();

        // then: Lua script called (sets active_jti, no previous to blacklist inside Lua)
        verify(redisTemplate).execute(any(RedisScript.class), anyList(), anyList());
        // tokenBlacklistService.blacklist is NOT called directly (Lua handles it)
        verify(tokenBlacklistService, never()).blacklist(anyString(), any(Duration.class));
    }

    @Test
    void login_invalidCredentials_throws() {
        // given: user exists but password wrong
        UserEntity entity = UserEntity.builder()
                .userId("user-3").username("user3")
                .encodedPassword("encoded").role("NURSE")
                .createdAt(LocalDateTime.now())
                .build();

        when(userRepository.findByUsername("user3")).thenReturn(Mono.just(entity));
        when(passwordEncoder.matches("wrongpass", "encoded")).thenReturn(false);

        // when & then
        var req = new AuthService.AuthRequest("user3", "wrongpass", null);

        StepVerifier.create(authService.login(req))
                .expectErrorSatisfies(err -> {
                    assertInstanceOf(IllegalArgumentException.class, err);
                    assertEquals("잘못된 사용자명 또는 비밀번호입니다", err.getMessage());
                })
                .verify();
    }

    // ── register ─────────────────────────────────────────────────

    @Test
    void register_setsActiveJti() {
        // given
        when(userRepository.existsByUsername("newreg")).thenReturn(Mono.just(false));
        when(passwordEncoder.encode("securepass")).thenReturn("encoded-pw");

        UserEntity saved = UserEntity.builder()
                .userId("gen-uuid").username("newreg")
                .encodedPassword("encoded-pw").role("NURSE")
                .createdAt(LocalDateTime.now())
                .build();
        when(userRepository.save(any(UserEntity.class))).thenReturn(Mono.just(saved));

        when(jwtUtil.generateToken(anyString(), eq("newreg"), eq("NURSE"))).thenReturn("reg-token");
        when(jwtUtil.getJti("reg-token")).thenReturn("reg-jti");

        // Lua script for rotateActiveJti — empty = first registration, no prev session
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyList()))
                .thenReturn(Flux.empty());

        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        try {
            lenient().when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (Exception ignored) {}
        lenient().when(valueOps.set(anyString(), anyString(), any(Duration.class)))
                .thenReturn(Mono.just(true));

        // when
        var req = new AuthService.AuthRequest("newreg", "securepass", null);

        StepVerifier.create(authService.register(req))
                .assertNext(resp -> {
                    assertEquals("reg-token", resp.token());
                    assertEquals("newreg", resp.username());
                })
                .verifyComplete();

        // then: Lua script executed to set active_jti
        verify(redisTemplate).execute(any(RedisScript.class), anyList(), anyList());
    }

    // ── logout ───────────────────────────────────────────────────

    @Test
    void logout_clearsActiveJti_andBlacklists() {
        // given
        String token = "some-jwt-token";
        when(jwtUtil.getJti(token)).thenReturn("logout-jti");
        when(jwtUtil.getUserId(token)).thenReturn("user-5");
        when(jwtUtil.getRemainingTtlMs(token)).thenReturn(1_800_000L);

        when(tokenBlacklistService.blacklist(eq("logout-jti"), any(Duration.class)))
                .thenReturn(Mono.just(true));
        when(redisTemplate.delete("chatflow:user:active_jti:user-5"))
                .thenReturn(Mono.just(1L));

        // when
        StepVerifier.create(authService.logout(token))
                .verifyComplete();

        // then
        verify(tokenBlacklistService).blacklist(eq("logout-jti"), eq(Duration.ofMillis(1_800_000L)));
        verify(redisTemplate).delete("chatflow:user:active_jti:user-5");
    }
}
