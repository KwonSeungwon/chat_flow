package com.chatflow.gateway.security;

import com.chatflow.gateway.entity.UserEntity;
import com.chatflow.gateway.repository.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
public class AuthService {

    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;
    private final TokenBlacklistService tokenBlacklistService;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final UserRepository userRepository;
    private final Duration cacheTtl;

    private static final String USER_KEY_PREFIX = "chatflow:user:";

    public AuthService(JwtUtil jwtUtil, PasswordEncoder passwordEncoder,
                       TokenBlacklistService tokenBlacklistService,
                       ReactiveStringRedisTemplate redisTemplate,
                       ObjectMapper objectMapper, UserRepository userRepository,
                       @Value("${jwt.expiration-ms:3600000}") long expirationMs) {
        this.jwtUtil = jwtUtil;
        this.passwordEncoder = passwordEncoder;
        this.tokenBlacklistService = tokenBlacklistService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.userRepository = userRepository;
        this.cacheTtl = Duration.ofMillis(expirationMs);
    }

    public record UserRecord(String userId, String username, String encodedPassword, String role, String profileImageUrl) {}
    public record AuthRequest(String username, String password, String role) {}
    public record AuthResponse(String token, String userId, String username, String role, String profileImageUrl) {}

    /**
     * 회원가입: DB write → cache write-through
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
                                UserRecord record = new UserRecord(saved.getUserId(), saved.getUsername(),
                                        saved.getEncodedPassword(), saved.getRole(), saved.getProfileImageUrl());
                                return cacheUser(saved.getUsername(), record)
                                        .then(Mono.fromCallable(() -> {
                                            String token = jwtUtil.generateToken(userId, request.username(), role);
                                            return new AuthResponse(token, userId, request.username(), role, null);
                                        }));
                            });
                });
    }

    /**
     * 로그인: Cache-Aside — cache hit → return, miss → DB → cache → return
     */
    public Mono<AuthResponse> login(AuthRequest request) {
        return getUserRecord(request.username())
                .switchIfEmpty(Mono.error(new IllegalArgumentException("잘못된 사용자명 또는 비밀번호입니다")))
                .flatMap(user -> {
                    if (!passwordEncoder.matches(request.password(), user.encodedPassword())) {
                        return Mono.error(new IllegalArgumentException("잘못된 사용자명 또는 비밀번호입니다"));
                    }
                    String role = user.role() != null ? user.role() : "NURSE";
                    String token = jwtUtil.generateToken(user.userId(), user.username(), role);
                    return Mono.just(new AuthResponse(token, user.userId(), user.username(), role, user.profileImageUrl()));
                });
    }

    /**
     * 프로필 이미지 변경: DB update → cache update
     */
    public Mono<Void> updateProfileImage(String username, String profileImageUrl) {
        return userRepository.findByUsername(username)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("사용자를 찾을 수 없습니다.")))
                .flatMap(entity -> {
                    entity.setProfileImageUrl(profileImageUrl);
                    return userRepository.save(entity);
                })
                .flatMap(saved -> {
                    UserRecord record = new UserRecord(saved.getUserId(), saved.getUsername(),
                            saved.getEncodedPassword(), saved.getRole(), saved.getProfileImageUrl());
                    return cacheUser(saved.getUsername(), record);
                })
                .then();
    }

    public Mono<Void> logout(String token) {
        String jti = jwtUtil.getJti(token);
        long remainingMs = jwtUtil.getRemainingTtlMs(token);
        return tokenBlacklistService.blacklist(jti, Duration.ofMillis(remainingMs))
                .then();
    }

    // ---- Cache-Aside helpers ----

    /**
     * Cache-Aside read: cache first, miss → DB → cache
     */
    private Mono<UserRecord> getUserRecord(String username) {
        String key = USER_KEY_PREFIX + username;
        return redisTemplate.opsForValue().get(key)
                .flatMap(json -> {
                    try {
                        return Mono.just(objectMapper.readValue(json, UserRecord.class));
                    } catch (JsonProcessingException e) {
                        log.warn("캐시 역직렬화 실패, DB fallback: {}", username);
                        return Mono.<UserRecord>empty();
                    }
                })
                .switchIfEmpty(
                    userRepository.findByUsername(username)
                            .flatMap(entity -> {
                                UserRecord record = new UserRecord(entity.getUserId(), entity.getUsername(),
                                        entity.getEncodedPassword(), entity.getRole(), entity.getProfileImageUrl());
                                return cacheUser(username, record).thenReturn(record);
                            })
                );
    }

    private Mono<Boolean> cacheUser(String username, UserRecord record) {
        String key = USER_KEY_PREFIX + username;
        try {
            String json = objectMapper.writeValueAsString(record);
            return redisTemplate.opsForValue().set(key, json, cacheTtl)
                    .onErrorResume(e -> {
                        log.warn("캐시 쓰기 실패 (무시): {}", e.getMessage());
                        return Mono.just(false);
                    });
        } catch (JsonProcessingException e) {
            log.warn("캐시 직렬화 실패: {}", e.getMessage());
            return Mono.just(false);
        }
    }
}
