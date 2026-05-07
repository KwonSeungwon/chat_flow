package com.chatflow.chat.exception;

import com.chatflow.common.dto.ErrorResponse;
import com.chatflow.common.exception.BaseExceptionHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends BaseExceptionHandler {

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(404, "NOT_FOUND", "요청한 리소스를 찾을 수 없습니다."));
    }

    // ── Operator Toolkit exceptions ─────────────────────────────

    @ExceptionHandler(PermissionDeniedException.class)
    public ResponseEntity<ErrorResponse> handlePermissionDenied(PermissionDeniedException e) {
        log.warn("Permission denied: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of(403, "PERMISSION_DENIED", e.getMessage()));
    }

    @ExceptionHandler(RoomTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleRoomTypeNotSupported(RoomTypeNotSupportedException e) {
        log.warn("Room type not supported: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(400, "ROOM_TYPE_NOT_SUPPORTED", e.getMessage()));
    }

    @ExceptionHandler(SelfTargetNotAllowedException.class)
    public ResponseEntity<ErrorResponse> handleSelfTarget(SelfTargetNotAllowedException e) {
        log.warn("Self target not allowed: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(400, "SELF_TARGET_NOT_ALLOWED", e.getMessage()));
    }

    @ExceptionHandler(SelfReportNotAllowedException.class)
    public ResponseEntity<ErrorResponse> handleSelfReport(SelfReportNotAllowedException e) {
        log.warn("Self report not allowed: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(400, "SELF_REPORT_NOT_ALLOWED", e.getMessage()));
    }

    @ExceptionHandler(ReportRateLimitException.class)
    public ResponseEntity<ErrorResponse> handleReportRateLimit(ReportRateLimitException e) {
        log.warn("Report rate limit exceeded: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ErrorResponse.of(429, "REPORT_RATE_LIMIT", e.getMessage()));
    }

    @ExceptionHandler(MessageNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleMessageNotFound(MessageNotFoundException e) {
        log.warn("Message not found: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(404, "MESSAGE_NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(MutedException.class)
    public ResponseEntity<ErrorResponse> handleMuted(MutedException e) {
        log.warn("User is muted: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.LOCKED)
                .body(ErrorResponse.of(423, "MUTED", e.getMessage()));
    }

    /**
     * scheduledAt parsing failures from ScheduledMessageController. Maps to a
     * stable 400 + machine-readable code so the frontend can surface a
     * format hint instead of relying on Jackson's raw exception text.
     */
    @ExceptionHandler(java.time.format.DateTimeParseException.class)
    public ResponseEntity<ErrorResponse> handleDateTimeParse(
            java.time.format.DateTimeParseException e) {
        log.warn("DateTimeParse error: {}", e.getMessage());
        return ResponseEntity.badRequest().body(ErrorResponse.of(
                400, "INVALID_DATETIME",
                "scheduledAt must be ISO-8601 LOCAL_DATE_TIME format (e.g. 2026-05-07T14:30:00)"));
    }

    /**
     * IllegalStateException for resource-cap / quota violations
     * (e.g. ScheduledMessageService.MAX_PENDING_PER_USER). 429 is the right
     * code for "you've hit the limit, slow down". Note: controllers that
     * use IllegalStateException for not-found masking (see
     * ScheduledMessageController.cancel) MUST catch it locally before it
     * reaches this handler.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException e) {
        log.warn("Illegal state (cap/quota): {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ErrorResponse.of(429, "QUOTA_EXCEEDED", e.getMessage()));
    }
}
