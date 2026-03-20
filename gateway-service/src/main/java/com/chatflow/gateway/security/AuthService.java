package com.chatflow.gateway.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;
    private final TokenBlacklistService tokenBlacklistService;

    // MVP: 인메모리 사용자 저장소 (추후 R2DBC/DB로 전환)
    private final Map<String, UserRecord> usersByUsername = new ConcurrentHashMap<>();

    public record UserRecord(String userId, String username, String encodedPassword) {}
    public record AuthRequest(String username, String password) {}
    public record AuthResponse(String token, String userId, String username) {}

    public Mono<AuthResponse> register(AuthRequest request) {
        return Mono.fromCallable(() -> {
            if (usersByUsername.containsKey(request.username())) {
                throw new IllegalArgumentException("이미 존재하는 사용자명입니다: " + request.username());
            }
            String userId = UUID.randomUUID().toString();
            String encoded = passwordEncoder.encode(request.password());
            usersByUsername.put(request.username(), new UserRecord(userId, request.username(), encoded));

            String token = jwtUtil.generateToken(userId, request.username());
            return new AuthResponse(token, userId, request.username());
        });
    }

    public Mono<AuthResponse> login(AuthRequest request) {
        return Mono.fromCallable(() -> {
            UserRecord user = usersByUsername.get(request.username());
            if (user == null || !passwordEncoder.matches(request.password(), user.encodedPassword())) {
                throw new IllegalArgumentException("잘못된 사용자명 또는 비밀번호입니다");
            }
            String token = jwtUtil.generateToken(user.userId(), user.username());
            return new AuthResponse(token, user.userId(), user.username());
        });
    }

    public Mono<Void> logout(String token) {
        String jti = jwtUtil.getJti(token);
        long remainingMs = jwtUtil.getRemainingTtlMs(token);
        return tokenBlacklistService.blacklist(jti, Duration.ofMillis(remainingMs))
                .then();
    }
}
