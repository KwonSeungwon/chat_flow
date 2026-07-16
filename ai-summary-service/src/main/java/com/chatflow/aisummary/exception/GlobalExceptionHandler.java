package com.chatflow.aisummary.exception;

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
}
