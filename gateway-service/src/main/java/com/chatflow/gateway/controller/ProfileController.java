package com.chatflow.gateway.controller;

import com.chatflow.gateway.dto.ProfileResponse;
import com.chatflow.gateway.dto.ProfileUpdateRequest;
import com.chatflow.gateway.entity.UserEntity;
import com.chatflow.gateway.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class ProfileController {

    private final UserRepository userRepository;

    /**
     * 본인 프로필 조회. JwtAuthenticationWebFilter가 X-User-Id 헤더에 userId를 주입한다.
     */
    @GetMapping("/me")
    public Mono<ResponseEntity<ProfileResponse>> getMe(ServerHttpRequest request) {
        String userId = request.getHeaders().getFirst("X-User-Id");
        if (userId == null || userId.isBlank()) {
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
        }
        return userRepository.findByUserId(userId)
                .map(ProfileResponse::from)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    /**
     * 본인 프로필 부분 수정.
     * null = 변경 없음, "" = 명시적 NULL 저장.
     */
    @PatchMapping("/me")
    public Mono<ResponseEntity<ProfileResponse>> updateMe(
            ServerHttpRequest request,
            @RequestBody ProfileUpdateRequest body) {
        String userId = request.getHeaders().getFirst("X-User-Id");
        if (userId == null || userId.isBlank()) {
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
        }
        try {
            body.validate();
        } catch (IllegalArgumentException e) {
            return Mono.just(ResponseEntity.badRequest().build());
        }

        return userRepository.findByUserId(userId)
                .switchIfEmpty(Mono.error(new IllegalStateException("User not found: " + userId)))
                .flatMap(user -> {
                    apply(user, body);
                    return userRepository.save(user);
                })
                .doOnSuccess(saved -> log.info("Profile updated: userId={}", saved.getUserId()))
                .map(ProfileResponse::from)
                .map(ResponseEntity::ok)
                .onErrorResume(IllegalStateException.class,
                        e -> Mono.just(ResponseEntity.notFound().build()));
    }

    /**
     * 다른 사용자 프로필 조회 (멤버 미리보기). 인증된 누구나 가능.
     */
    @GetMapping("/{userId}")
    public Mono<ResponseEntity<ProfileResponse>> getById(@PathVariable("userId") String userId,
                                                         ServerHttpRequest request) {
        String caller = request.getHeaders().getFirst("X-User-Id");
        if (caller == null || caller.isBlank()) {
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
        }
        return userRepository.findByUserId(userId)
                .map(ProfileResponse::from)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    private void apply(UserEntity user, ProfileUpdateRequest body) {
        if (body.profileImageUrl() != null) {
            user.setProfileImageUrl(body.profileImageUrl().isEmpty() ? null : body.profileImageUrl());
        }
        if (body.statusMessage() != null) {
            user.setStatusMessage(body.statusMessage().isEmpty() ? null : body.statusMessage());
        }
        if (body.bio() != null) {
            user.setBio(body.bio().isEmpty() ? null : body.bio());
        }
    }
}
