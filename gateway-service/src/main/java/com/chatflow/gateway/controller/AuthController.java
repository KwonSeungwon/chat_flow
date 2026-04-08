package com.chatflow.gateway.controller;

import com.chatflow.gateway.security.AuthService;
import com.chatflow.gateway.security.AuthService.AuthRequest;
import com.chatflow.gateway.security.AuthService.AuthResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.*;
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
        String bearerToken = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (bearerToken == null || !bearerToken.startsWith("Bearer ")) {
            return Mono.just(ResponseEntity.status(401).build());
        }
        String username = body.get("username");
        String profileImageUrl = body.get("profileImageUrl");
        if (username == null || profileImageUrl == null) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
        return authService.updateProfileImage(username, profileImageUrl)
                .then(Mono.just(ResponseEntity.ok(Map.of("profileImageUrl", profileImageUrl))))
                .onErrorResume(e -> Mono.just(ResponseEntity.badRequest().build()));
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
