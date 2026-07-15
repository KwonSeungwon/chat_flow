package com.chatflow.chat.exception;

import com.chatflow.common.dto.ErrorResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    // ── Direct handler unit tests (existing) ────────────────────

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

    @Test
    void handleQuotaExceeded_returns429() {
        ResponseEntity<ErrorResponse> response =
                handler.handleQuotaExceeded(new QuotaExceededException("limit reached"));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("QUOTA_EXCEEDED", response.getBody().getCode());
        assertEquals("limit reached", response.getBody().getMessage());
    }

    // ── MockMvc tests: framework 4xx inherited from BaseExceptionHandler ──

    /**
     * Dummy controller that triggers the framework conditions we want to test.
     * standaloneSetup + setControllerAdvice(GlobalExceptionHandler) proves the
     * inheritance chain (Base → Global) works end-to-end.
     */
    @RestController
    @RequestMapping("/test")
    static class DummyController {

        @GetMapping("/with-header")
        public String withHeader(@RequestHeader("X-Required") String required) {
            return "ok:" + required;
        }

        @GetMapping("/with-param")
        public String withParam(@RequestParam("q") String q) {
            return "ok:" + q;
        }

        @GetMapping("/typed/{id}")
        public String typed(@PathVariable Long id) {
            return "ok:" + id;
        }

        @PostMapping("/post-only")
        public String postOnly() {
            return "ok";
        }

        @GetMapping("/quota")
        public String quota() {
            throw new QuotaExceededException("scheduled message limit reached (max 100)");
        }

        @GetMapping("/illegal-state")
        public String illegalState() {
            throw new IllegalStateException("Cannot call value() on Failure");
        }
    }

    @Nested
    class MockMvcFramework4xxTests {

        private MockMvc mockMvc;

        @BeforeEach
        void setUp() {
            mockMvc = MockMvcBuilders.standaloneSetup(new DummyController())
                    .setControllerAdvice(new GlobalExceptionHandler())
                    .build();
        }

        @Test
        void missingRequiredHeader_returns400_MISSING_REQUEST_PARAMETER() throws Exception {
            mockMvc.perform(get("/test/with-header"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MISSING_REQUEST_PARAMETER"))
                    .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        void missingRequiredParam_returns400_MISSING_REQUEST_PARAMETER() throws Exception {
            mockMvc.perform(get("/test/with-param"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MISSING_REQUEST_PARAMETER"))
                    .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        void typeMismatch_returns400_TYPE_MISMATCH() throws Exception {
            mockMvc.perform(get("/test/typed/abc"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("TYPE_MISMATCH"))
                    .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        void unsupportedMethod_returns405_METHOD_NOT_ALLOWED() throws Exception {
            // /test/post-only accepts POST only; GET should be 405
            mockMvc.perform(get("/test/post-only"))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                    .andExpect(jsonPath("$.status").value(405));
        }

        @Test
        void quotaExceeded_returns429() throws Exception {
            mockMvc.perform(get("/test/quota"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.code").value("QUOTA_EXCEEDED"))
                    .andExpect(jsonPath("$.status").value(429));
        }

        @Test
        void illegalStateException_fallsThrough_to500_notQuota429() throws Exception {
            // Before this fix, IllegalStateException mapped to 429 QUOTA_EXCEEDED.
            // Now it must fall through to the catch-all → 500 INTERNAL_ERROR.
            mockMvc.perform(get("/test/illegal-state"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                    .andExpect(jsonPath("$.status").value(500));
        }
    }
}
