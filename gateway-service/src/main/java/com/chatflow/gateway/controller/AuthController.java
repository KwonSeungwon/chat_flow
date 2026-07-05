package com.chatflow.gateway.controller;

import com.chatflow.gateway.security.AuthService;
import com.chatflow.gateway.security.AuthService.AuthRequest;
import com.chatflow.gateway.security.AuthService.AuthResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public Mono<ResponseEntity<AuthResponse>> register(@RequestBody AuthRequest request) {
        return authService.register(request)
                .map(ResponseEntity::ok)
                .onErrorResume(IllegalArgumentException.class,
                        e -> Mono.just(ResponseEntity.badRequest().build()));
    }

    @PostMapping("/login")
    public Mono<ResponseEntity<AuthResponse>> login(@RequestBody AuthRequest request) {
        return authService.login(request)
                .map(ResponseEntity::ok)
                .onErrorResume(IllegalArgumentException.class,
                        e -> Mono.just(ResponseEntity.status(401).build()));
    }

    @PutMapping("/profile")
    public Mono<ResponseEntity<Map<String, String>>> updateProfile(
            @RequestBody Map<String, String> body, ServerHttpRequest request) {
        // Identity comes from JwtAuthenticationWebFilter-injected headers (blacklist-gated)
        String username = getAuthenticatedUsername(request);
        if (username == null) {
            return Mono.just(ResponseEntity.status(401).build());
        }
        String profileImageUrl = body.get("profileImageUrl");
        if (profileImageUrl == null) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
        return authService.updateProfileImage(username, profileImageUrl)
                .then(Mono.just(ResponseEntity.ok(Map.of("profileImageUrl", profileImageUrl))))
                .onErrorResume(e -> Mono.just(ResponseEntity.badRequest().build()));
    }

    @PutMapping("/password")
    public Mono<ResponseEntity<Map<String, String>>> changePassword(
            @RequestBody Map<String, String> body, ServerHttpRequest request) {
        // Identity comes from JwtAuthenticationWebFilter-injected headers (blacklist-gated)
        String username = getAuthenticatedUsername(request);
        if (username == null) {
            return Mono.just(ResponseEntity.status(401).build());
        }
        String currentPassword = body.get("currentPassword");
        String newPassword = body.get("newPassword");
        if (currentPassword == null || newPassword == null) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
        return authService.changePassword(username, currentPassword, newPassword)
                .then(Mono.just(ResponseEntity.ok(Map.of("message", "비밀번호가 변경되었습니다."))))
                .onErrorResume(IllegalArgumentException.class,
                        e -> Mono.just(ResponseEntity.badRequest().body(Map.of("error", e.getMessage()))));
    }

    /**
     * Read the authenticated username from filter-injected X-Username header.
     * The header is URL-encoded by JwtAuthenticationWebFilter and only present
     * for authenticated, non-blacklisted requests (client-supplied copies are sanitized).
     */
    private String getAuthenticatedUsername(ServerHttpRequest request) {
        String encoded = request.getHeaders().getFirst("X-Username");
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
    }

    @PostMapping("/logout")
    public Mono<ResponseEntity<Void>> logout(ServerHttpRequest request) {
        String bearerToken = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (bearerToken == null || !bearerToken.startsWith("Bearer ")) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
        String token = bearerToken.substring(7);
        return authService.logout(token)
                .then(Mono.just(ResponseEntity.ok().<Void>build()))
                .onErrorResume(e -> Mono.just(ResponseEntity.badRequest().build()));
    }
}
