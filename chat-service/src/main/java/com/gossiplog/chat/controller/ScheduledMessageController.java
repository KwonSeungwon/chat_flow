package com.gossiplog.chat.controller;

import com.gossiplog.chat.auth.AuthenticatedUser;
import com.gossiplog.chat.auth.RequireAuth;
import com.gossiplog.chat.dto.ScheduledMessageDto;
import com.gossiplog.chat.mapper.ScheduledMessageMapper;
import com.gossiplog.chat.service.notification.ScheduledMessageService;
import com.gossiplog.common.dto.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
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
                parseScheduledAt(request.scheduledAt()));
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

    /**
     * Accepts ISO-8601 with an offset ({@code 2026-08-01T09:00:00+09:00},
     * {@code 2026-08-01T00:00:00.000Z}) and converts it to the absolute instant.
     * A naive value without offset ({@code 2026-08-01T00:00:00}) is still accepted for
     * older clients and interpreted as UTC.
     * A {@link DateTimeParseException} propagates → 400 INVALID_DATETIME.
     */
    static Instant parseScheduledAt(String raw) {
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (DateTimeParseException withoutOffset) {
            return LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC);
        }
    }

    // ── Request record ──────────────────────────────────────────
    // JSON keys match frontend Dio payload: chatRoomId, content, scheduledAt.
    // scheduledAt stays String — parsed by parseScheduledAt();
    // DateTimeParseException is mapped to 400 INVALID_DATETIME by GlobalExceptionHandler.

    public record ScheduleRequest(
            @NotBlank String chatRoomId,
            @NotBlank String content,
            @NotBlank String scheduledAt
    ) {}
}
