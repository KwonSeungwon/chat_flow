package com.chatflow.aisummary.exception;

import com.chatflow.aisummary.client.MembershipCheckUnavailableException;
import com.chatflow.common.dto.ApiResponse;
import com.chatflow.common.exception.BaseExceptionHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends BaseExceptionHandler {

    /**
     * Bucket4j AI rate-limit gate -> 429 with ApiResponse body.
     *
     * The response shape intentionally uses {@link ApiResponse} (not
     * {@link com.chatflow.common.dto.ErrorResponse}) to preserve wire
     * compatibility with the controller-local catch that this handler replaces.
     */
    @ExceptionHandler(AiRateLimitException.class)
    public ResponseEntity<ApiResponse<?>> handleAiRateLimit(AiRateLimitException e) {
        log.warn("AI rate limit exceeded: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ApiResponse.error(e.getMessage()));
    }

    @ExceptionHandler(UnauthenticatedException.class)
    public ResponseEntity<ApiResponse<?>> handleUnauthenticated(UnauthenticatedException e) {
        log.warn("Unauthenticated AI request: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error("인증이 필요합니다."));
    }

    @ExceptionHandler(RoomAccessDeniedException.class)
    public ResponseEntity<ApiResponse<?>> handleRoomAccessDenied(RoomAccessDeniedException e) {
        log.warn("Room access denied: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("방 멤버가 아닙니다."));
    }

    @ExceptionHandler(MembershipCheckUnavailableException.class)
    public ResponseEntity<ApiResponse<?>> handleMembershipUnavailable(MembershipCheckUnavailableException e) {
        log.error("Membership check unavailable: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error("권한 확인을 할 수 없습니다. 잠시 후 다시 시도해 주세요."));
    }
}
