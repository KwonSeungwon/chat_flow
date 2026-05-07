package com.chatflow.chat.controller;

import com.chatflow.chat.dto.MentionItemDto;
import com.chatflow.chat.service.MentionDigestService;
import com.chatflow.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/chat/mentions")
@RequiredArgsConstructor
public class MentionDigestController {

    private final MentionDigestService service;

    @GetMapping
    public ResponseEntity<ApiResponse<List<MentionItemDto>>> list(
            @RequestHeader(value = "X-User-Id") String userId,
            @RequestHeader(value = "X-Username", required = false) String username,
            @RequestParam(defaultValue = "30") int days) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("X-Username header is required");
        }
        return ResponseEntity.ok(ApiResponse.ok(service.list(userId, username, days)));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<ApiResponse<Map<String, Long>>> unreadCount(
            @RequestHeader(value = "X-User-Id") String userId,
            @RequestHeader(value = "X-Username", required = false) String username,
            @RequestParam(defaultValue = "30") int days) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("X-Username header is required");
        }
        long count = service.unreadCount(userId, username, days);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("count", count)));
    }

    @PostMapping("/{messageId}/read")
    public ResponseEntity<ApiResponse<Void>> markRead(
            @PathVariable String messageId,
            @RequestHeader(value = "X-User-Id") String userId) {
        service.markRead(userId, messageId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PostMapping("/read-all")
    public ResponseEntity<ApiResponse<Void>> markAllRead(
            @RequestHeader(value = "X-User-Id") String userId,
            @RequestHeader(value = "X-Username", required = false) String username,
            @RequestParam(defaultValue = "30") int days) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("X-Username header is required");
        }
        service.markAllRead(userId, username, days);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
