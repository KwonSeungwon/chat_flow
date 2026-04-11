package com.chatflow.gateway.controller;

import com.chatflow.gateway.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;

    /**
     * 사용자 검색 — username 포함 검색, 최대 10명 반환.
     * 멤버 초대 모달에서 사용.
     */
    @GetMapping("/search")
    public Mono<ResponseEntity<Map<String, Object>>> searchUsers(
            @RequestParam(defaultValue = "") String q) {
        if (q.isBlank() || q.trim().length() < 2) {
            return Mono.just(ResponseEntity.ok(Map.of("data", List.of())));
        }
        return userRepository
                .findByUsernameContainingIgnoreCaseOrderByUsernameAsc(q.trim())
                .take(10)
                .map(u -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("userId", u.getUserId() != null ? u.getUserId() : "");
                    entry.put("username", u.getUsername() != null ? u.getUsername() : "");
                    entry.put("profileImageUrl", u.getProfileImageUrl() != null ? u.getProfileImageUrl() : "");
                    return entry;
                })
                .collectList()
                .map(list -> ResponseEntity.ok(Map.of("data", (Object) list)));
    }
}
