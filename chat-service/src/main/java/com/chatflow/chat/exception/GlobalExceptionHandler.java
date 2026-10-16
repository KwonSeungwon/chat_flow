package com.chatflow.chat.exception;

import com.chatflow.chat.game.exception.GameException;
import com.chatflow.common.dto.ApiResponse;
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

    // ── Stage 3-A auth handlers (ApiResponse shape — mirrors pre-refactor
    // controller bodies; Operator Toolkit handlers below use ErrorResponse
    // intentionally for stable error codes). Do not normalize.
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiResponse<?>> handleUnauthorized(UnauthorizedException e) {
        log.warn("Unauthorized: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error(e.getMessage()));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiResponse<?>> handleForbidden(ForbiddenException e) {
        log.warn("Forbidden: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(e.getMessage()));
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
     * QuotaExceededException for resource-cap / quota violations
     * (e.g. ScheduledMessageService.MAX_PENDING_PER_USER). 429 is the right
     * code for "you've hit the limit, slow down".
     *
     * Previously this handler caught ALL IllegalStateException → 429, which
     * incorrectly mapped programming errors (e.g. Result.value() on Failure)
     * to 429 instead of 500. Now only the dedicated QuotaExceededException
     * triggers 429; stray IllegalStateExceptions fall through to the
     * catch-all → 500 (correct — they are bugs, not client rate limits).
     */
    @ExceptionHandler(QuotaExceededException.class)
    public ResponseEntity<ErrorResponse> handleQuotaExceeded(QuotaExceededException e) {
        log.warn("Quota exceeded: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ErrorResponse.of(429, "QUOTA_EXCEEDED", e.getMessage()));
    }

    /** A constraint the request should not have broken (length, uniqueness) — client error, not 500. */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(
            org.springframework.dao.DataIntegrityViolationException e) {
        log.warn("Data integrity violation: {}", e.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(409, "DATA_CONFLICT", "요청 값이 저장 규칙에 맞지 않습니다."));
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUploadSize(
            org.springframework.web.multipart.MaxUploadSizeExceededException e) {
        log.warn("Upload too large: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ErrorResponse.of(413, "PAYLOAD_TOO_LARGE", "파일이 허용 크기를 초과합니다."));
    }

    /** Wiki Dungeon game errors carry their own status and stable code. */
    @ExceptionHandler(GameException.class)
    public ResponseEntity<ErrorResponse> handleGame(GameException e) {
        log.warn("Game error {}: {}", e.getCode(), e.getMessage());
        return ResponseEntity.status(e.getStatus())
                .body(ErrorResponse.of(e.getStatus(), e.getCode(), e.getMessage()));
    }
}
