package com.chatflow.gateway.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;
    private final TokenBlacklistService tokenBlacklistService;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String USER_KEY_PREFIX = "chatflow:user:";

    public record UserRecord(String userId, String username, String encodedPassword, String role, String profileImageUrl) {}
    public record AuthRequest(String username, String password, String role) {}
    public record AuthResponse(String token, String userId, String username, String role, String profileImageUrl) {}

    public Mono<AuthResponse> register(AuthRequest request) {
        if (request.password() == null || request.password().length() < 8) {
            return Mono.error(new IllegalArgumentException("비밀번호는 8자 이상이어야 합니다."));
        }
        String key = USER_KEY_PREFIX + request.username();
        return redisTemplate.opsForValue().get(key)
                .flatMap(existing -> Mono.<AuthResponse>error(
                        new IllegalArgumentException("이미 존재하는 사용자명입니다: " + request.username())))
                .switchIfEmpty(Mono.defer(() -> {
                    String userId = UUID.randomUUID().toString();
                    String encoded = passwordEncoder.encode(request.password());
                    String role = request.role() != null ? request.role() : "NURSE";
                    UserRecord user = new UserRecord(userId, request.username(), encoded, role, null);
                    String json;
                    try {
                        json = objectMapper.writeValueAsString(user);
                    } catch (JsonProcessingException e) {
                        return Mono.error(e);
                    }
                    return redisTemplate.opsForValue().set(key, json)
                            .then(Mono.fromCallable(() -> {
                                String token = jwtUtil.generateToken(userId, request.username(), role);
                                return new AuthResponse(token, userId, request.username(), role, null);
                            }));
                }));
    }

    public Mono<AuthResponse> login(AuthRequest request) {
        String key = USER_KEY_PREFIX + request.username();
        return redisTemplate.opsForValue().get(key)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("잘못된 사용자명 또는 비밀번호입니다")))
                .flatMap(json -> {
                    UserRecord user;
                    try {
                        user = objectMapper.readValue(json, UserRecord.class);
                    } catch (JsonProcessingException e) {
                        return Mono.error(e);
                    }
                    if (!passwordEncoder.matches(request.password(), user.encodedPassword())) {
                        return Mono.error(new IllegalArgumentException("잘못된 사용자명 또는 비밀번호입니다"));
                    }
                    String role = user.role() != null ? user.role() : "NURSE";
                    String token = jwtUtil.generateToken(user.userId(), user.username(), role);
                    return Mono.just(new AuthResponse(token, user.userId(), user.username(), role, user.profileImageUrl()));
                });
    }

    public Mono<Void> updateProfileImage(String username, String profileImageUrl) {
        String key = USER_KEY_PREFIX + username;
        return redisTemplate.opsForValue().get(key)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("사용자를 찾을 수 없습니다.")))
                .flatMap(json -> {
                    UserRecord user;
                    try {
                        user = objectMapper.readValue(json, UserRecord.class);
                    } catch (JsonProcessingException e) {
                        return Mono.error(e);
                    }
                    UserRecord updated = new UserRecord(user.userId(), user.username(), user.encodedPassword(), user.role(), profileImageUrl);
                    String updatedJson;
                    try {
                        updatedJson = objectMapper.writeValueAsString(updated);
                    } catch (JsonProcessingException e) {
                        return Mono.error(e);
                    }
                    return redisTemplate.opsForValue().set(key, updatedJson).then();
                });
    }

    public Mono<Void> logout(String token) {
        String jti = jwtUtil.getJti(token);
        long remainingMs = jwtUtil.getRemainingTtlMs(token);
        return tokenBlacklistService.blacklist(jti, Duration.ofMillis(remainingMs))
                .then();
    }
}
