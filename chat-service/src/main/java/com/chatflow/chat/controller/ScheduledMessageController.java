package com.chatflow.chat.controller;

import com.chatflow.chat.dto.ScheduledMessageDto;
import com.chatflow.chat.service.ScheduledMessageService;
import com.chatflow.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
            @RequestHeader(value = "X-Username") String username) {
        String chatRoomId = (String) body.get("chatRoomId");
        String content = (String) body.get("content");
        String scheduledAtStr = (String) body.get("scheduledAt");
        if (chatRoomId == null || chatRoomId.isBlank()
                || content == null || content.isBlank()
                || scheduledAtStr == null || scheduledAtStr.isBlank()) {
            return ResponseEntity.badRequest().body(
                    ApiResponse.error("chatRoomId, content, scheduledAt are required"));
        }
        try {
            var saved = service.schedule(chatRoomId, userId, username, content,
                    LocalDateTime.parse(scheduledAtStr));
            return ResponseEntity.ok(ApiResponse.ok(ScheduledMessageDto.from(saved)));
        } catch (IllegalArgumentException e) {
            // schedule() throws this for past times
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        } catch (java.time.format.DateTimeParseException e) {
            return ResponseEntity.badRequest().body(
                    ApiResponse.error("scheduledAt must be ISO-8601 format"));
        }
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
            return ResponseEntity.ok(ApiResponse.ok(ScheduledMessageDto.from(canceled)));
        } catch (IllegalStateException e) {
            // Not found OR not owned — same response shape, no info leak
            return ResponseEntity.status(404).body(
                    ApiResponse.error("Scheduled message not found"));
        }
    }
}
