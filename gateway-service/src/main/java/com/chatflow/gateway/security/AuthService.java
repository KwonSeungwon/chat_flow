package com.chatflow.gateway.security;

import com.chatflow.common.security.SecurityKeys;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.chatflow.gateway.entity.UserEntity;
import com.chatflow.gateway.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.chatflow.gateway.config.LoginRateLimitFilter;
import com.chatflow.gateway.dto.ProfileUpdateRequest;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Service
public class AuthService {

    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;
    private final TokenBlacklistService tokenBlacklistService;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final UserRepository userRepository;
    private final Duration cacheTtl;

    /**
     * Lua script: atomic rotate-active-jti.
     * GET → blacklist old (if different) → SET new — single Redis round-trip.
     * Returns: invalidated prev jti (or nil if no previous session).
     *
     * KEYS[1]: active_jti key  (chatflow:user:active_jti:{userId})
     * KEYS[2]: blacklist prefix (chatflow:blacklist:)
     * ARGV[1]: newJti
     * ARGV[2]: ttl in seconds
     * ARGV[3]: blacklist value ("1")
     */
    private static final RedisScript<String> ROTATE_JTI_SCRIPT = RedisScript.of(
            "local prev = redis.call('GET', KEYS[1])\n" +
            "local invalidated = nil\n" +
            "if prev and prev ~= '' and prev ~= ARGV[1] then\n" +
            "    redis.call('SET', KEYS[2] .. prev, ARGV[3], 'EX', ARGV[2])\n" +
            "    invalidated = prev\n" +
            "end\n" +
            "redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])\n" +
            "return invalidated",
            String.class
    );

    /** 현재 active jti가 이 토큰의 것일 때만 지운다 — 옛 토큰의 logout이 새 세션을 끊지 못하게 한다. */
    private static final RedisScript<Long> CLEAR_ACTIVE_JTI_IF_MATCH_SCRIPT = RedisScript.of(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end\n" +
            "return 0",
            Long.class
    );

    /** 신규 가입에만 적용한다. 기존 사용자명은 이 규칙 밖일 수 있어 로그인에는 쓰지 않는다. */
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9가-힣_.-]{2,20}$");
    private static final int MAX_PASSWORD_BYTES = 72; // bcrypt 입력 한계
    private static final String PASSWORD_RATE_KEY_PREFIX = "rl:pwd:";
    private static final int PASSWORD_WINDOW_SECONDS = 60;
    private static final int PASSWORD_MAX_ATTEMPTS = 5;
    private static final String SIGNUP_LOCK_PREFIX = "chatflow:signup:lock:";
    private static final Duration SIGNUP_LOCK_TTL = Duration.ofSeconds(10);

    /** 없는 사용자도 bcrypt 비교를 한 번 돌려 응답 시간으로 존재 여부가 드러나지 않게 한다. */
    private final String dummyEncodedPassword;

    public AuthService(JwtUtil jwtUtil, PasswordEncoder passwordEncoder,
                       TokenBlacklistService tokenBlacklistService,
                       ReactiveStringRedisTemplate redisTemplate,
                       UserRepository userRepository,
                       @Value("${jwt.expiration-ms:3600000}") long expirationMs) {
        this.jwtUtil = jwtUtil;
        this.passwordEncoder = passwordEncoder;
        this.tokenBlacklistService = tokenBlacklistService;
        this.redisTemplate = redisTemplate;
        this.userRepository = userRepository;
        this.cacheTtl = Duration.ofMillis(expirationMs);
        this.dummyEncodedPassword = passwordEncoder.encode("chatflow-dummy-password");
    }

    /** 옛 클라이언트가 보내는 role 등 알 수 없는 속성은 무시한다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AuthRequest(String username, String password) {}
    public record AuthResponse(String token, String userId, String username, String profileImageUrl) {}

    /**
     * 회원가입: DB write → active JTI 등록 → 토큰 발급
     */
    public Mono<AuthResponse> register(AuthRequest request) {
        String password = request.password();
        if (password == null || password.length() < 8) {
            return Mono.error(new IllegalArgumentException("비밀번호는 8자 이상이어야 합니다."));
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            return Mono.error(new IllegalArgumentException("비밀번호가 너무 깁니다. (최대 72바이트)"));
        }
        String username = request.username() == null ? "" : request.username().trim();
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            return Mono.error(new IllegalArgumentException(
                    "아이디는 2~20자의 한글, 영문, 숫자, _ . - 만 사용할 수 있습니다."));
        }
        // 대소문자만 다른 이름의 동시 가입은 DB UNIQUE로 못 막는다(대소문자 구분 컬럼). lower(username) 키로 직렬화한다.
        String lockKey = SIGNUP_LOCK_PREFIX + username.toLowerCase(Locale.ROOT);
        return acquireSignupLock(lockKey)
                .flatMap(acquired -> acquired
                        ? Mono.usingWhen(Mono.just(lockKey),
                                k -> doRegister(username, password),
                                this::releaseSignupLock)
                        : Mono.<AuthResponse>error(new ResponseStatusException(
                                HttpStatus.CONFLICT, "이미 존재하는 사용자명입니다.")));
    }

    /** Redis 장애 시 fail-open — 기존처럼 existsBy 검사와 DB 제약에 맡긴다. */
    private Mono<Boolean> acquireSignupLock(String key) {
        return redisTemplate.opsForValue().setIfAbsent(key, "1", SIGNUP_LOCK_TTL)
                .defaultIfEmpty(true)
                .onErrorResume(e -> {
                    log.warn("Signup lock failed (fail-open): {}", e.getMessage());
                    return Mono.just(true);
                });
    }

    private Mono<Void> releaseSignupLock(String key) {
        return redisTemplate.opsForValue().delete(key)
                .onErrorResume(e -> Mono.empty())
                .then();
    }

    private Mono<AuthResponse> doRegister(String username, String password) {
        return userRepository.existsByUsernameIgnoreCase(username)
                .flatMap(exists -> {
                    if (exists) {
                        return Mono.<AuthResponse>error(
                                new IllegalArgumentException("이미 존재하는 사용자명입니다: " + username));
                    }
                    String userId = UUID.randomUUID().toString();

                    return Mono.fromCallable(() -> passwordEncoder.encode(password))
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(encoded -> {
                                UserEntity entity = UserEntity.builder()
                                        .userId(userId)
                                        .username(username)
                                        .encodedPassword(encoded)
                                        .createdAt(LocalDateTime.now())
                                        .build();

                                return userRepository.save(entity)
                                        .onErrorMap(DataIntegrityViolationException.class,
                                                e -> new ResponseStatusException(HttpStatus.CONFLICT, "이미 존재하는 사용자명입니다."))
                                        .flatMap(saved -> {
                                            String token = jwtUtil.generateToken(userId, username);
                                            String newJti = jwtUtil.getJti(token);
                                            return rotateActiveJti(userId, newJti, cacheTtl)
                                                    .thenReturn(new AuthResponse(token, userId, username, null));
                                        });
                            });
                });
    }

    /**
     * 로그인: DB 비밀번호 검증 → active JTI 교체 (이전 세션 무효화) → 토큰 발급
     */
    public Mono<AuthResponse> login(AuthRequest request) {
        // 비밀번호 검증은 항상 DB에서 수행
        return userRepository.findByUsername(request.username())
                .switchIfEmpty(Mono.defer(() ->
                        Mono.fromCallable(() -> passwordEncoder.matches(
                                        request.password() == null ? "" : request.password(), dummyEncodedPassword))
                                .subscribeOn(Schedulers.boundedElastic())
                                .then(Mono.<UserEntity>error(new IllegalArgumentException("잘못된 사용자명 또는 비밀번호입니다")))))
                .flatMap(entity ->
                    Mono.fromCallable(() -> passwordEncoder.matches(request.password(), entity.getEncodedPassword()))
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(matches -> {
                                if (!matches) {
                                    return Mono.error(new IllegalArgumentException("잘못된 사용자명 또는 비밀번호입니다"));
                                }
                                String token = jwtUtil.generateToken(entity.getUserId(), entity.getUsername());
                                String newJti = jwtUtil.getJti(token);
                                return rotateActiveJti(entity.getUserId(), newJti, cacheTtl)
                                        .thenReturn(new AuthResponse(token, entity.getUserId(), entity.getUsername(), entity.getProfileImageUrl()));
                            })
                );
    }

    /**
     * 프로필 이미지 변경: DB update
     */
    public Mono<Void> updateProfileImage(String username, String profileImageUrl) {
        try {
            new ProfileUpdateRequest(profileImageUrl, null, null).validate();
        } catch (IllegalArgumentException e) {
            return Mono.error(e);
        }
        return userRepository.findByUsername(username)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("사용자를 찾을 수 없습니다.")))
                .flatMap(entity -> userRepository.updateProfileImageUrl(entity.getUserId(), profileImageUrl))
                .then();
    }

    /**
     * 비밀번호 변경 후 새 세션 토큰을 발급한다. 현재 토큰은 블랙리스트하고 active jti를 새 토큰으로 회전해
     * (스크립트가 이전 active 세션을 블랙리스트) 다른 기기 세션도 무효화한다.
     *
     * @param currentJti 이 요청을 보낸 토큰의 jti. null이면 블랙리스트는 건너뛴다.
     * @return 새 JWT
     */
    public Mono<String> changePassword(String username, String currentPassword, String newPassword, String currentJti) {
        if (newPassword == null || newPassword.length() < 8) {
            return Mono.error(new IllegalArgumentException("새 비밀번호는 8자 이상이어야 합니다."));
        }
        if (newPassword.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            return Mono.error(new IllegalArgumentException("새 비밀번호가 너무 깁니다. (최대 72바이트)"));
        }
        return checkPasswordAttempts(username)
                // 비밀번호 검증은 항상 DB에서 수행
                .then(Mono.defer(() -> userRepository.findByUsername(username)))
                .switchIfEmpty(Mono.error(new IllegalArgumentException("사용자를 찾을 수 없습니다.")))
                .flatMap(entity ->
                    Mono.fromCallable(() -> {
                                if (!passwordEncoder.matches(currentPassword, entity.getEncodedPassword())) {
                                    throw new IllegalArgumentException("현재 비밀번호가 올바르지 않습니다.");
                                }
                                return passwordEncoder.encode(newPassword);
                            })
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(encoded -> userRepository.updateEncodedPassword(entity.getUserId(), encoded))
                            .flatMap(updated -> {
                                String token = jwtUtil.generateToken(entity.getUserId(), entity.getUsername());
                                String newJti = jwtUtil.getJti(token);
                                Mono<Void> blacklistCurrent = currentJti == null
                                        ? Mono.<Void>empty()
                                        : tokenBlacklistService.blacklist(currentJti, cacheTtl).then();
                                return blacklistCurrent
                                        .then(rotateActiveJti(entity.getUserId(), newJti, cacheTtl))
                                        // 비밀번호는 이미 저장됐다. 세션 전환 실패가 500으로 보이면 안 된다.
                                        .onErrorResume(e -> {
                                            log.warn("[auth] session rotation after password change failed: userId={} {}",
                                                    entity.getUserId(), e.getMessage());
                                            return Mono.empty();
                                        })
                                        .thenReturn(token);
                            })
                );
    }

    /** 사용자당 분당 5회. Redis 장애 시 fail-open. */
    private Mono<Void> checkPasswordAttempts(String username) {
        return redisTemplate.execute(LoginRateLimitFilter.INCR_EXPIRE_SCRIPT,
                        List.of(PASSWORD_RATE_KEY_PREFIX + username),
                        List.of(String.valueOf(PASSWORD_WINDOW_SECONDS)))
                .next()
                .onErrorResume(e -> {
                    log.warn("Password rate limit check failed (fail-open): {}", e.getMessage());
                    return Mono.empty();
                })
                .flatMap(count -> count > PASSWORD_MAX_ATTEMPTS
                        ? Mono.<Void>error(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                                "비밀번호 변경 시도가 너무 많습니다. 잠시 후 다시 시도해주세요."))
                        : Mono.<Void>empty());
    }

    /** 만료·위조 토큰이면 JwtException/IllegalArgumentException을 Mono 에러로 낸다. */
    public Mono<Void> logout(String token) {
        return Mono.defer(() -> {
            String jti = jwtUtil.getJti(token);
            String userId = jwtUtil.getUserId(token);
            long remainingMs = jwtUtil.getRemainingTtlMs(token);
            if (jti == null) {
                return Mono.<Void>empty(); // jti 없는 레거시 토큰: 블랙리스트할 키가 없다
            }
            // 이미 블랙리스트된 토큰(다른 기기 로그인 등으로 무효화됨)은 아무것도 하지 않는다.
            return tokenBlacklistService.isBlacklisted(jti)
                    .flatMap(blacklisted -> blacklisted
                            ? Mono.<Void>empty()
                            : tokenBlacklistService.blacklist(jti, Duration.ofMillis(remainingMs))
                                    .then(clearActiveJti(userId, jti)));
        });
    }

    // ---- Active JTI tracking (single-session enforcement) ----

    /**
     * 이전 active jti를 blacklist에 등록하고 새 jti를 active로 저장.
     * 새 로그인 시 기존 디바이스의 토큰을 무효화하여 단일 세션을 강제한다.
     *
     * Redis Lua script로 atomic 실행 — 동시 멀티 로그인 race condition 방지.
     */
    private Mono<Void> rotateActiveJti(String userId, String newJti, Duration ttl) {
        String activeKey = SecurityKeys.ACTIVE_JTI_PREFIX + userId;
        long ttlSeconds = Math.max(1, ttl.toSeconds());
        return redisTemplate.execute(
                ROTATE_JTI_SCRIPT,
                List.of(activeKey, SecurityKeys.BLACKLIST_PREFIX),
                List.of(newJti, String.valueOf(ttlSeconds), "1")
        )
        .next()
        .doOnNext(prevJti -> log.info("[auth] previous session invalidated: userId={} prevJti={}", userId, prevJti))
        .then();
    }

    private Mono<Void> clearActiveJti(String userId, String jti) {
        if (userId == null || jti == null) return Mono.empty();
        return redisTemplate.execute(CLEAR_ACTIVE_JTI_IF_MATCH_SCRIPT,
                        List.of(SecurityKeys.ACTIVE_JTI_PREFIX + userId), List.of(jti))
                .then();
    }

}
