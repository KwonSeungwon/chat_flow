package com.chatflow.chat.controller;

import com.chatflow.chat.dto.ScheduledMessageDto;
import com.chatflow.chat.service.ScheduledMessageService;
import com.chatflow.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/chat/scheduled-messages")
@RequiredArgsConstructor
public class ScheduledMessageController {

    private final ScheduledMessageService service;

    @PostMapping
    public ResponseEntity<ApiResponse<ScheduledMessageDto>> schedule(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-User-Id") String userId,
            @RequestHeader(value = "X-Username", required = false) String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("X-Username header is required");
        }
        String chatRoomId = (String) body.get("chatRoomId");
        String content = (String) body.get("content");
        String scheduledAtStr = (String) body.get("scheduledAt");
        if (chatRoomId == null || chatRoomId.isBlank()) {
            throw new IllegalArgumentException("chatRoomId is required");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content is required");
        }
        if (scheduledAtStr == null || scheduledAtStr.isBlank()) {
            throw new IllegalArgumentException("scheduledAt is required");
        }
        var saved = service.schedule(chatRoomId, userId, username, content,
                LocalDateTime.parse(scheduledAtStr));
        log.info("Scheduled message id={} for user={} room={} at={}",
                saved.getId(), userId, chatRoomId, saved.getScheduledAt());
        return ResponseEntity.ok(ApiResponse.ok(ScheduledMessageDto.from(saved)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<ScheduledMessageDto>>> list(
            @RequestHeader(value = "X-User-Id") String userId) {
        var items = service.listMine(userId).stream()
                .map(ScheduledMessageDto::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(items));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<ScheduledMessageDto>> cancel(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Id") String userId) {
        try {
            var canceled = service.cancel(id, userId);
            log.info("Scheduled message id={} canceled by user={}", id, userId);
            return ResponseEntity.ok(ApiResponse.ok(ScheduledMessageDto.from(canceled)));
        } catch (IllegalStateException e) {
            // Not-found OR not-owned — same response shape, no info leak.
            // The byte-equality test in ScheduledMessageControllerTest locks this invariant.
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    ApiResponse.error("Scheduled message not found"));
        }
    }
}
