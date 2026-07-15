package com.chatflow.chat.controller;

import com.chatflow.chat.auth.AuthenticatedUser;
import com.chatflow.chat.auth.RequireAuth;
import com.chatflow.chat.dto.ScheduledMessageDto;
import com.chatflow.chat.mapper.ScheduledMessageMapper;
import com.chatflow.chat.service.notification.ScheduledMessageService;
import com.chatflow.common.dto.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/chat/scheduled-messages")
@RequiredArgsConstructor
public class ScheduledMessageController {

    private final ScheduledMessageService service;
    private final ScheduledMessageMapper scheduledMessageMapper;

    @RequireAuth
    @PostMapping
    public ResponseEntity<ApiResponse<ScheduledMessageDto>> schedule(
            @Valid @RequestBody ScheduleRequest request,
            @AuthenticatedUser String userId,
            @RequestHeader(value = "X-Username", required = false) String username) {
        var saved = service.schedule(request.chatRoomId(), userId, username, request.content(),
                LocalDateTime.parse(request.scheduledAt()));
        log.info("Scheduled message id={} for user={} room={} at={}",
                saved.getId(), userId, request.chatRoomId(), saved.getScheduledAt());
        return ResponseEntity.ok(ApiResponse.ok(scheduledMessageMapper.toDto(saved)));
    }

    @RequireAuth
    @GetMapping
    public ResponseEntity<ApiResponse<List<ScheduledMessageDto>>> list(
            @AuthenticatedUser String userId) {
        var items = service.listMine(userId).stream()
                .map(scheduledMessageMapper::toDto)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(items));
    }

    @RequireAuth
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<ScheduledMessageDto>> cancel(
            @PathVariable Long id,
            @AuthenticatedUser String userId) {
        try {
            var canceled = service.cancel(id, userId);
            log.info("Scheduled message id={} canceled by user={}", id, userId);
            return ResponseEntity.ok(ApiResponse.ok(scheduledMessageMapper.toDto(canceled)));
        } catch (IllegalStateException e) {
            // Not-found OR not-owned — same response shape, no info leak.
            // The byte-equality test in ScheduledMessageControllerTest locks this invariant.
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    ApiResponse.error("Scheduled message not found"));
        }
    }

    // ── Request record ──────────────────────────────────────────
    // JSON keys match frontend Dio payload: chatRoomId, content, scheduledAt.
    // scheduledAt stays String — parsed downstream by LocalDateTime.parse();
    // DateTimeParseException is mapped to 400 INVALID_DATETIME by GlobalExceptionHandler.

    public record ScheduleRequest(
            @NotBlank String chatRoomId,
            @NotBlank String content,
            @NotBlank String scheduledAt
    ) {}
}
