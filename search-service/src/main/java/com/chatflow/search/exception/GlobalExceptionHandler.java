package com.chatflow.search.exception;

import com.chatflow.common.dto.ErrorResponse;
import com.chatflow.common.exception.BaseExceptionHandler;
import lombok.extern.slf4j.Slf4j;
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
}
