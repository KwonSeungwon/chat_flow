package com.chatflow.chat.exception;

import com.chatflow.common.dto.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handlePermissionDenied_returns403() {
        ResponseEntity<ErrorResponse> response =
                handler.handlePermissionDenied(new PermissionDeniedException("no access"));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("PERMISSION_DENIED", response.getBody().getCode());
        assertEquals("no access", response.getBody().getMessage());
    }

    @Test
    void handleRoomTypeNotSupported_returns400() {
        ResponseEntity<ErrorResponse> response =
                handler.handleRoomTypeNotSupported(new RoomTypeNotSupportedException("DM"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("ROOM_TYPE_NOT_SUPPORTED", response.getBody().getCode());
    }

    @Test
    void handleSelfTarget_returns400() {
        ResponseEntity<ErrorResponse> response =
                handler.handleSelfTarget(new SelfTargetNotAllowedException("self"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("SELF_TARGET_NOT_ALLOWED", response.getBody().getCode());
    }

    @Test
    void handleSelfReport_returns400() {
        ResponseEntity<ErrorResponse> response =
                handler.handleSelfReport(new SelfReportNotAllowedException("own msg"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("SELF_REPORT_NOT_ALLOWED", response.getBody().getCode());
    }

    @Test
    void handleReportRateLimit_returns429() {
        ResponseEntity<ErrorResponse> response =
                handler.handleReportRateLimit(new ReportRateLimitException("rate limit"));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("REPORT_RATE_LIMIT", response.getBody().getCode());
    }

    @Test
    void handleMessageNotFound_returns404() {
        ResponseEntity<ErrorResponse> response =
                handler.handleMessageNotFound(new MessageNotFoundException("not found"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("MESSAGE_NOT_FOUND", response.getBody().getCode());
    }

    @Test
    void handleMuted_returns423() {
        ResponseEntity<ErrorResponse> response =
                handler.handleMuted(new MutedException("muted until..."));

        assertEquals(HttpStatus.LOCKED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("MUTED", response.getBody().getCode());
        assertEquals(423, response.getBody().getStatus());
    }
}
