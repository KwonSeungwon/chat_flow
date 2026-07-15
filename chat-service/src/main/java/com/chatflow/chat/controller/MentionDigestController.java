package com.chatflow.chat.controller;

import com.chatflow.chat.auth.AuthenticatedUser;
import com.chatflow.chat.auth.RequireAuth;
import com.chatflow.chat.dto.MentionItemDto;
import com.chatflow.chat.service.notification.MentionDigestService;
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

    @RequireAuth
    @GetMapping
    public ResponseEntity<ApiResponse<List<MentionItemDto>>> list(
            @AuthenticatedUser String userId,
            @RequestHeader(value = "X-Username", required = false) String username,
            @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(ApiResponse.ok(service.list(userId, username, days)));
    }

    @RequireAuth
    @GetMapping("/unread-count")
    public ResponseEntity<ApiResponse<Map<String, Long>>> unreadCount(
            @AuthenticatedUser String userId,
            @RequestHeader(value = "X-Username", required = false) String username,
            @RequestParam(defaultValue = "30") int days) {
        long count = service.unreadCount(userId, username, days);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("count", count)));
    }

    @RequireAuth
    @PostMapping("/{messageId}/read")
    public ResponseEntity<ApiResponse<Void>> markRead(
            @PathVariable String messageId,
            @AuthenticatedUser String userId) {
        service.markRead(userId, messageId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @RequireAuth
    @PostMapping("/read-all")
    public ResponseEntity<ApiResponse<Void>> markAllRead(
            @AuthenticatedUser String userId,
            @RequestHeader(value = "X-Username", required = false) String username,
            @RequestParam(defaultValue = "30") int days) {
        service.markAllRead(userId, username, days);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
