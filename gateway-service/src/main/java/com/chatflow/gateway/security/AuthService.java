package com.chatflow.gateway.security;

import com.chatflow.common.security.SecurityKeys;
import com.chatflow.gateway.entity.UserEntity;
import com.chatflow.gateway.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

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
    }

    public record AuthRequest(String username, String password, String role) {}
    public record AuthResponse(String token, String userId, String username, String role, String profileImageUrl) {}

    /**
     * 회원가입: DB write → active JTI 등록 → 토큰 발급
     */
    public Mono<AuthResponse> register(AuthRequest request) {
        if (request.password() == null || request.password().length() < 8) {
            return Mono.error(new IllegalArgumentException("비밀번호는 8자 이상이어야 합니다."));
        }
        return userRepository.existsByUsername(request.username())
                .flatMap(exists -> {
                    if (exists) {
                        return Mono.<AuthResponse>error(
                                new IllegalArgumentException("이미 존재하는 사용자명입니다: " + request.username()));
                    }
                    String userId = UUID.randomUUID().toString();
                    String encoded = passwordEncoder.encode(request.password());
                    String role = request.role() != null ? request.role() : "NURSE";

                    UserEntity entity = UserEntity.builder()
                            .userId(userId)
                            .username(request.username())
                            .encodedPassword(encoded)
                            .role(role)
                            .createdAt(LocalDateTime.now())
                            .build();

                    return userRepository.save(entity)
                            .flatMap(saved -> {
                                String token = jwtUtil.generateToken(userId, request.username(), role);
                                String newJti = jwtUtil.getJti(token);
                                return rotateActiveJti(userId, newJti, cacheTtl)
                                        .thenReturn(new AuthResponse(token, userId, request.username(), role, null));
                            });
                });
    }

    /**
     * 로그인: DB 비밀번호 검증 → active JTI 교체 (이전 세션 무효화) → 토큰 발급
     */
    public Mono<AuthResponse> login(AuthRequest request) {
        // 비밀번호 검증은 항상 DB에서 수행
        return userRepository.findByUsername(request.username())
                .switchIfEmpty(Mono.error(new IllegalArgumentException("잘못된 사용자명 또는 비밀번호입니다")))
                .flatMap(entity -> {
                    if (!passwordEncoder.matches(request.password(), entity.getEncodedPassword())) {
                        return Mono.error(new IllegalArgumentException("잘못된 사용자명 또는 비밀번호입니다"));
                    }
                    String role = entity.getRole() != null ? entity.getRole() : "NURSE";
                    String token = jwtUtil.generateToken(entity.getUserId(), entity.getUsername(), role);
                    String newJti = jwtUtil.getJti(token);
                    return rotateActiveJti(entity.getUserId(), newJti, cacheTtl)
                            .thenReturn(new AuthResponse(token, entity.getUserId(), entity.getUsername(), role, entity.getProfileImageUrl()));
                });
    }

    /**
     * 프로필 이미지 변경: DB update
     */
    public Mono<Void> updateProfileImage(String username, String profileImageUrl) {
        return userRepository.findByUsername(username)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("사용자를 찾을 수 없습니다.")))
                .flatMap(entity -> {
                    entity.setProfileImageUrl(profileImageUrl);
                    return userRepository.save(entity);
                })
                .then();
    }

    public Mono<Void> changePassword(String username, String currentPassword, String newPassword) {
        if (newPassword == null || newPassword.length() < 8) {
            return Mono.error(new IllegalArgumentException("새 비밀번호는 8자 이상이어야 합니다."));
        }
        // 비밀번호 검증은 항상 DB에서 수행
        return userRepository.findByUsername(username)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("사용자를 찾을 수 없습니다.")))
                .flatMap(entity -> {
                    if (!passwordEncoder.matches(currentPassword, entity.getEncodedPassword())) {
                        return Mono.error(new IllegalArgumentException("현재 비밀번호가 올바르지 않습니다."));
                    }
                    entity.setEncodedPassword(passwordEncoder.encode(newPassword));
                    return userRepository.save(entity);
                })
                .then();
    }

    public Mono<Void> logout(String token) {
        String jti = jwtUtil.getJti(token);
        String userId = jwtUtil.getUserId(token);
        long remainingMs = jwtUtil.getRemainingTtlMs(token);
        return tokenBlacklistService.blacklist(jti, Duration.ofMillis(remainingMs))
                .then(clearActiveJti(userId));
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

    private Mono<Void> clearActiveJti(String userId) {
        if (userId == null) return Mono.empty();
        return redisTemplate.delete(SecurityKeys.ACTIVE_JTI_PREFIX + userId).then();
    }

}
