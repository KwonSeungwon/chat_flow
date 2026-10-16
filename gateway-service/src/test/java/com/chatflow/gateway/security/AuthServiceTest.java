package com.chatflow.gateway.security;

import com.chatflow.gateway.entity.UserEntity;
import com.chatflow.gateway.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.security.crypto.password.PasswordEncoder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private JwtUtil jwtUtil;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private TokenBlacklistService tokenBlacklistService;
    @Mock private ReactiveStringRedisTemplate redisTemplate;
    @Mock private UserRepository userRepository;

    private AuthService authService;

    private static final long EXPIRATION_MS = 3_600_000L;

    @BeforeEach
    void setUp() {
        authService = new AuthService(
                jwtUtil, passwordEncoder, tokenBlacklistService,
                redisTemplate, userRepository, EXPIRATION_MS);
    }

    // ── register ─────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void register_oldClientSendsRole_isIgnored() throws Exception {
        // 옛 클라이언트는 요청에 role을 실어 보낸다. 역직렬화가 실패하지 않고 가입이 성공해야 한다.
        var req = new com.fasterxml.jackson.databind.ObjectMapper()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
                .readValue("{\"username\":\"evil\",\"password\":\"password123\",\"role\":\"ADMIN\"}",
                        AuthService.AuthRequest.class);

        when(userRepository.existsByUsernameIgnoreCase("evil")).thenReturn(Mono.just(false));
        when(passwordEncoder.encode("password123")).thenReturn("enc");
        when(userRepository.save(any(UserEntity.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(jwtUtil.generateToken(anyString(), eq("evil"))).thenReturn("tok");
        when(jwtUtil.getJti("tok")).thenReturn("jti");
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyList()))
                .thenReturn(Flux.empty());

        StepVerifier.create(authService.register(req))
                .assertNext(resp -> assertEquals("tok", resp.token()))
                .verifyComplete();
    }

    // ── login ────────────────────────────────────────────────────

    @Test
    void login_invalidatesPreviousJti() {
        // given: user exists with valid password
        UserEntity entity = UserEntity.builder()
                .userId("user-1").username("user1")
                .encodedPassword("encoded")
                .profileImageUrl(null).createdAt(LocalDateTime.now())
                .build();

        lenient().when(userRepository.findByUsername("user1")).thenReturn(Mono.just(entity));
        when(passwordEncoder.matches("pass1234", "encoded")).thenReturn(true);
        when(jwtUtil.generateToken("user-1", "user1")).thenReturn("new-token");
        when(jwtUtil.getJti("new-token")).thenReturn("new-jti-123");

        // Lua script execution (rotateActiveJti) — returns prev jti or empty
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyList()))
                .thenReturn(Flux.just("old-jti-prev"));

        // when
        var req = new AuthService.AuthRequest("user1", "pass1234");

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
                .encodedPassword("enc")
                .createdAt(LocalDateTime.now())
                .build();

        when(userRepository.findByUsername("newuser")).thenReturn(Mono.just(entity));
        when(passwordEncoder.matches("password", "enc")).thenReturn(true);
        when(jwtUtil.generateToken("user-2", "newuser")).thenReturn("token-abc");
        when(jwtUtil.getJti("token-abc")).thenReturn("jti-first");

        // Lua script handles both first-time and rotation atomically — empty = no prev session
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyList()))
                .thenReturn(Flux.empty());

        // when
        var req = new AuthService.AuthRequest("newuser", "password");

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
                .encodedPassword("encoded")
                .createdAt(LocalDateTime.now())
                .build();

        when(userRepository.findByUsername("user3")).thenReturn(Mono.just(entity));
        when(passwordEncoder.matches("wrongpass", "encoded")).thenReturn(false);

        // when & then
        var req = new AuthService.AuthRequest("user3", "wrongpass");

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
        when(userRepository.existsByUsernameIgnoreCase("newreg")).thenReturn(Mono.just(false));
        when(passwordEncoder.encode("securepass")).thenReturn("encoded-pw");

        UserEntity saved = UserEntity.builder()
                .userId("gen-uuid").username("newreg")
                .encodedPassword("encoded-pw")
                .createdAt(LocalDateTime.now())
                .build();
        when(userRepository.save(any(UserEntity.class))).thenReturn(Mono.just(saved));

        when(jwtUtil.generateToken(anyString(), eq("newreg"))).thenReturn("reg-token");
        when(jwtUtil.getJti("reg-token")).thenReturn("reg-jti");

        // Lua script for rotateActiveJti — empty = first registration, no prev session
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyList()))
                .thenReturn(Flux.empty());

        // when
        var req = new AuthService.AuthRequest("newreg", "securepass");

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
    @SuppressWarnings("unchecked")
    void logout_clearsActiveJtiOnlyIfItMatchesTokenJti_andBlacklists() {
        String token = "some-jwt-token";
        when(jwtUtil.getJti(token)).thenReturn("logout-jti");
        when(jwtUtil.getUserId(token)).thenReturn("user-5");
        when(jwtUtil.getRemainingTtlMs(token)).thenReturn(1_800_000L);
        when(tokenBlacklistService.isBlacklisted("logout-jti")).thenReturn(Mono.just(false));
        when(tokenBlacklistService.blacklist(eq("logout-jti"), any(Duration.class)))
                .thenReturn(Mono.just(true));
        when(redisTemplate.execute(any(RedisScript.class),
                eq(List.of("chatflow:user:active_jti:user-5")), eq(List.of("logout-jti"))))
                .thenReturn(Flux.just(1L));

        StepVerifier.create(authService.logout(token)).verifyComplete();

        verify(tokenBlacklistService).blacklist(eq("logout-jti"), eq(Duration.ofMillis(1_800_000L)));
        verify(redisTemplate).execute(any(RedisScript.class),
                eq(List.of("chatflow:user:active_jti:user-5")), eq(List.of("logout-jti")));
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void logout_alreadyBlacklistedToken_isNoOp() {
        // 옛 토큰의 logout이 새 세션의 active_jti를 지우면 안 된다
        String token = "old-token";
        when(jwtUtil.getJti(token)).thenReturn("old-jti");
        when(jwtUtil.getUserId(token)).thenReturn("user-6");
        when(jwtUtil.getRemainingTtlMs(token)).thenReturn(1_000L);
        when(tokenBlacklistService.isBlacklisted("old-jti")).thenReturn(Mono.just(true));

        StepVerifier.create(authService.logout(token)).verifyComplete();

        verify(tokenBlacklistService, never()).blacklist(anyString(), any(Duration.class));
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void logout_expiredToken_failsAsMonoErrorNotSyncThrow() {
        when(jwtUtil.getJti("expired")).thenThrow(new io.jsonwebtoken.ExpiredJwtException(null, null, "expired"));

        Mono<Void> result = authService.logout("expired");

        StepVerifier.create(result)
                .expectError(io.jsonwebtoken.JwtException.class)
                .verify();
    }

    // ── register validation (신규 가입에만 적용) ───────────────────

    @Test
    void register_invalidUsernames_rejected() {
        for (String bad : new String[] {"a", "x".repeat(21), "has space", "bad!char", "a\n", "a/b", "  ", null}) {
            StepVerifier.create(authService.register(new AuthService.AuthRequest(bad, "password123")))
                    .expectErrorSatisfies(err -> assertInstanceOf(IllegalArgumentException.class, err))
                    .verify();
        }
        verify(userRepository, never()).save(any(UserEntity.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void register_trimsUsername_andAcceptsAllowedCharset() {
        when(userRepository.existsByUsernameIgnoreCase("김철수_a.b-1")).thenReturn(Mono.just(false));
        when(passwordEncoder.encode("password123")).thenReturn("enc");
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(jwtUtil.generateToken(anyString(), eq("김철수_a.b-1"))).thenReturn("tok");
        when(jwtUtil.getJti("tok")).thenReturn("jti");
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyList())).thenReturn(Flux.empty());

        StepVerifier.create(authService.register(new AuthService.AuthRequest("  김철수_a.b-1 ", "password123")))
                .assertNext(resp -> assertEquals("김철수_a.b-1", resp.username()))
                .verifyComplete();
    }

    @Test
    void register_caseInsensitiveDuplicate_rejected() {
        when(userRepository.existsByUsernameIgnoreCase("Alice")).thenReturn(Mono.just(true));

        StepVerifier.create(authService.register(new AuthService.AuthRequest("Alice", "password123")))
                .expectErrorSatisfies(err -> assertInstanceOf(IllegalArgumentException.class, err))
                .verify();
        verify(userRepository, never()).save(any(UserEntity.class));
    }

    @Test
    void register_duplicateRace_mapsTo409() {
        when(userRepository.existsByUsernameIgnoreCase("racer")).thenReturn(Mono.just(false));
        when(passwordEncoder.encode("password123")).thenReturn("enc");
        when(userRepository.save(any(UserEntity.class)))
                .thenReturn(Mono.error(new org.springframework.dao.DataIntegrityViolationException("dup")));

        StepVerifier.create(authService.register(new AuthService.AuthRequest("racer", "password123")))
                .expectErrorSatisfies(err -> {
                    var rse = assertInstanceOf(org.springframework.web.server.ResponseStatusException.class, err);
                    assertEquals(409, rse.getStatusCode().value());
                })
                .verify();
    }

    @Test
    void register_passwordOver72Bytes_rejected() {
        String longPw = "가".repeat(25); // 75 bytes
        StepVerifier.create(authService.register(new AuthService.AuthRequest("validname", longPw)))
                .expectErrorSatisfies(err -> assertInstanceOf(IllegalArgumentException.class, err))
                .verify();
        verify(userRepository, never()).save(any(UserEntity.class));
    }

    @Test
    void login_legacyUsernameOutsideNewRule_stillWorks() {
        UserEntity entity = UserEntity.builder()
                .userId("legacy-1").username("a b!").encodedPassword("enc")
                .createdAt(LocalDateTime.now()).build();
        when(userRepository.findByUsername("a b!")).thenReturn(Mono.just(entity));
        when(passwordEncoder.matches("pass1234", "enc")).thenReturn(true);
        when(jwtUtil.generateToken("legacy-1", "a b!")).thenReturn("legacy-token");
        when(jwtUtil.getJti("legacy-token")).thenReturn("legacy-jti");
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyList())).thenReturn(Flux.empty());

        StepVerifier.create(authService.login(new AuthService.AuthRequest("a b!", "pass1234")))
                .assertNext(resp -> assertEquals("a b!", resp.username()))
                .verifyComplete();
    }

    @Test
    void login_unknownUser_stillRunsBcryptCompare() {
        when(userRepository.findByUsername("ghost")).thenReturn(Mono.empty());

        StepVerifier.create(authService.login(new AuthService.AuthRequest("ghost", "whatever1")))
                .expectErrorSatisfies(err -> assertEquals("잘못된 사용자명 또는 비밀번호입니다", err.getMessage()))
                .verify();

        verify(passwordEncoder).matches(eq("whatever1"), any());
    }

    // ── changePassword: 시도 제한 / 세션 고정 ───────────────────────

    @SuppressWarnings("unchecked")
    private void allowPasswordAttempt() {
        when(redisTemplate.execute(eq(com.chatflow.gateway.config.LoginRateLimitFilter.INCR_EXPIRE_SCRIPT),
                anyList(), anyList())).thenReturn(Flux.just(1L));
    }

    @Test
    @SuppressWarnings("unchecked")
    void changePassword_sixthAttemptInWindow_returns429() {
        when(redisTemplate.execute(eq(com.chatflow.gateway.config.LoginRateLimitFilter.INCR_EXPIRE_SCRIPT),
                eq(List.of("rl:pwd:cpuser3")), anyList())).thenReturn(Flux.just(6L));

        StepVerifier.create(authService.changePassword("cpuser3", "a", "newpasswd", null))
                .expectErrorSatisfies(err -> {
                    var rse = assertInstanceOf(org.springframework.web.server.ResponseStatusException.class, err);
                    assertEquals(429, rse.getStatusCode().value());
                })
                .verify();
        verifyNoInteractions(userRepository);
    }

    @Test
    @SuppressWarnings("unchecked")
    void changePassword_pinsActiveJtiToCurrentToken() {
        UserEntity entity = UserEntity.builder()
                .userId("user-cp4").username("cpuser4").encodedPassword("old-enc")
                .createdAt(LocalDateTime.now()).build();
        allowPasswordAttempt();
        when(userRepository.findByUsername("cpuser4")).thenReturn(Mono.just(entity));
        when(passwordEncoder.matches("currentpw", "old-enc")).thenReturn(true);
        when(passwordEncoder.encode("newpasswd")).thenReturn("new-enc");
        when(userRepository.save(any(UserEntity.class))).thenReturn(Mono.just(entity));
        // 회전 스크립트: 현재 토큰과 다른 active jti가 있었다면 그것이 무효화되어 반환된다
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of("chatflow:user:active_jti:user-cp4",
                "chatflow:blacklist:")), anyList())).thenReturn(Flux.just("stale-jti"));

        StepVerifier.create(authService.changePassword("cpuser4", "currentpw", "newpasswd", "cur-jti"))
                .verifyComplete();

        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of("chatflow:user:active_jti:user-cp4",
                "chatflow:blacklist:")), eq(List.of("cur-jti", "3600", "1")));
    }

    // ── threading: BCrypt must run off the event loop ─────────────

    @Test
    void register_runsBCryptOnBoundedElastic() {
        // Capture the thread that executes passwordEncoder.encode
        AtomicReference<String> encodeThread = new AtomicReference<>();

        when(userRepository.existsByUsernameIgnoreCase("threadtest")).thenReturn(Mono.just(false));
        when(passwordEncoder.encode("password123")).thenAnswer(inv -> {
            encodeThread.set(Thread.currentThread().getName());
            return "encoded-thread";
        });

        UserEntity saved = UserEntity.builder()
                .userId("t-uuid").username("threadtest")
                .encodedPassword("encoded-thread")
                .createdAt(LocalDateTime.now())
                .build();
        when(userRepository.save(any(UserEntity.class))).thenReturn(Mono.just(saved));
        when(jwtUtil.generateToken(anyString(), eq("threadtest"))).thenReturn("t-token");
        when(jwtUtil.getJti("t-token")).thenReturn("t-jti");
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyList()))
                .thenReturn(Flux.empty());

        var req = new AuthService.AuthRequest("threadtest", "password123");

        StepVerifier.create(authService.register(req))
                .assertNext(resp -> assertEquals("t-token", resp.token()))
                .verifyComplete();

        assertNotNull(encodeThread.get(), "passwordEncoder.encode should have been called");
        assertTrue(encodeThread.get().startsWith("boundedElastic"),
                "BCrypt encode should run on boundedElastic, but ran on: " + encodeThread.get());
    }

    @Test
    void login_runsBCryptOnBoundedElastic() {
        // Capture the thread that executes passwordEncoder.matches
        AtomicReference<String> matchesThread = new AtomicReference<>();

        UserEntity entity = UserEntity.builder()
                .userId("user-t").username("threaduser")
                .encodedPassword("enc")
                .createdAt(LocalDateTime.now())
                .build();

        when(userRepository.findByUsername("threaduser")).thenReturn(Mono.just(entity));
        when(passwordEncoder.matches("pass1234", "enc")).thenAnswer(inv -> {
            matchesThread.set(Thread.currentThread().getName());
            return true;
        });
        when(jwtUtil.generateToken("user-t", "threaduser")).thenReturn("t-token2");
        when(jwtUtil.getJti("t-token2")).thenReturn("t-jti2");
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyList()))
                .thenReturn(Flux.empty());

        var req = new AuthService.AuthRequest("threaduser", "pass1234");

        StepVerifier.create(authService.login(req))
                .assertNext(resp -> assertEquals("threaduser", resp.username()))
                .verifyComplete();

        assertNotNull(matchesThread.get(), "passwordEncoder.matches should have been called");
        assertTrue(matchesThread.get().startsWith("boundedElastic"),
                "BCrypt matches should run on boundedElastic, but ran on: " + matchesThread.get());
    }

    @Test
    void changePassword_runsBCryptOnBoundedElastic() {
        // Capture threads for both matches and encode in changePassword
        AtomicReference<String> matchesThread = new AtomicReference<>();
        AtomicReference<String> encodeThread = new AtomicReference<>();

        UserEntity entity = UserEntity.builder()
                .userId("user-cp").username("cpuser")
                .encodedPassword("old-enc")
                .createdAt(LocalDateTime.now())
                .build();

        allowPasswordAttempt();
        when(userRepository.findByUsername("cpuser")).thenReturn(Mono.just(entity));
        when(passwordEncoder.matches("currentpw", "old-enc")).thenAnswer(inv -> {
            matchesThread.set(Thread.currentThread().getName());
            return true;
        });
        when(passwordEncoder.encode("newpasswd")).thenAnswer(inv -> {
            encodeThread.set(Thread.currentThread().getName());
            return "new-enc";
        });
        when(userRepository.save(any(UserEntity.class))).thenReturn(Mono.just(entity));

        StepVerifier.create(authService.changePassword("cpuser", "currentpw", "newpasswd", null))
                .verifyComplete();

        assertNotNull(matchesThread.get(), "passwordEncoder.matches should have been called");
        assertTrue(matchesThread.get().startsWith("boundedElastic"),
                "BCrypt matches should run on boundedElastic, but ran on: " + matchesThread.get());

        assertNotNull(encodeThread.get(), "passwordEncoder.encode should have been called");
        assertTrue(encodeThread.get().startsWith("boundedElastic"),
                "BCrypt encode should run on boundedElastic, but ran on: " + encodeThread.get());
    }

    @Test
    void changePassword_wrongCurrentPassword_errorsWithSameMessage() {
        // The mismatch now throws inside the offloaded Callable (was Mono.error) —
        // pin that it still surfaces as the same IllegalArgumentException + message
        // and that encode/save never run.
        UserEntity entity = UserEntity.builder()
                .userId("user-cp2").username("cpuser2")
                .encodedPassword("old-enc")
                .createdAt(LocalDateTime.now())
                .build();

        allowPasswordAttempt();
        when(userRepository.findByUsername("cpuser2")).thenReturn(Mono.just(entity));
        when(passwordEncoder.matches("wrongpw", "old-enc")).thenReturn(false);

        StepVerifier.create(authService.changePassword("cpuser2", "wrongpw", "newpasswd", null))
                .expectErrorSatisfies(err -> {
                    assertInstanceOf(IllegalArgumentException.class, err);
                    assertEquals("현재 비밀번호가 올바르지 않습니다.", err.getMessage());
                })
                .verify(Duration.ofSeconds(5));

        verify(passwordEncoder, never()).encode("newpasswd");
        verify(userRepository, never()).save(any(UserEntity.class));
    }
}
