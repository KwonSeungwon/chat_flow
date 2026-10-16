package com.chatflow.search.exception;

import com.chatflow.common.dto.ErrorResponse;
import com.chatflow.search.client.MembershipCheckUnavailableException;
import com.chatflow.common.exception.BaseExceptionHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends BaseExceptionHandler {

    @ExceptionHandler(SearchException.class)
    public ResponseEntity<ErrorResponse> handleSearchException(SearchException e) {
        log.error("Search error: {}", e.getMessage(), e);
        return ResponseEntity.internalServerError()
                .body(ErrorResponse.of(500, "SEARCH_ERROR", e.getMessage()));
    }

    @ExceptionHandler(UnauthenticatedException.class)
    public ResponseEntity<ErrorResponse> handleUnauthenticated(UnauthenticatedException e) {
        log.warn("Unauthenticated search request: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.of(401, "UNAUTHORIZED", "인증이 필요합니다."));
    }

    @ExceptionHandler(RoomAccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleRoomAccessDenied(RoomAccessDeniedException e) {
        log.warn("Room access denied: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of(403, "FORBIDDEN", "방 멤버가 아닙니다."));
    }

    @ExceptionHandler(MembershipCheckUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleMembershipUnavailable(MembershipCheckUnavailableException e) {
        log.error("Membership check unavailable: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ErrorResponse.of(503, "MEMBERSHIP_UNAVAILABLE", "권한 확인을 할 수 없습니다. 잠시 후 다시 시도해 주세요."));
    }
}
