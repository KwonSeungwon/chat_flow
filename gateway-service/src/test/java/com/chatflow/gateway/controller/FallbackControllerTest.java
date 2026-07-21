package com.chatflow.gateway.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test for FallbackController.
 *
 * Verifies each circuit-breaker fallback endpoint:
 * - Returns 503 Service Unavailable status
 * - Body contains expected fields: status, code, service, message, retryAfterSeconds, timestamp
 * - Retry-After header is set correctly per service
 * - Service name is correctly identified
 */
@ExtendWith(MockitoExtension.class)
class FallbackControllerTest {

    @InjectMocks
    private FallbackController controller;

    // ── Chat fallback ───────────────────────────────────────────

    @Test
    void chatFallback_returns503WithCorrectBody() {
        StepVerifier.create(controller.chatFallback())
                .assertNext(response -> {
                    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
                    assertEquals("30", response.getHeaders().getFirst("Retry-After"));

                    Map<String, Object> body = response.getBody();
                    assertNotNull(body);
                    assertEquals(503, body.get("status"));
                    assertEquals("SERVICE_UNAVAILABLE", body.get("code"));
                    assertEquals("chat-service", body.get("service"));
                    assertEquals("채팅 서비스가 일시적으로 불안정합니다. 잠시 후 다시 시도해주세요.",
                            body.get("message"));
                    assertEquals(30, body.get("retryAfterSeconds"));
                    assertNotNull(body.get("timestamp"));
                })
                .verifyComplete();
    }

    // ── AI Summary fallback ─────────────────────────────────────

    @Test
    void aiSummaryFallback_returns503WithRetryAfter60() {
        StepVerifier.create(controller.aiSummaryFallback())
                .assertNext(response -> {
                    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
                    assertEquals("60", response.getHeaders().getFirst("Retry-After"));

                    Map<String, Object> body = response.getBody();
                    assertNotNull(body);
                    assertEquals(503, body.get("status"));
                    assertEquals("SERVICE_UNAVAILABLE", body.get("code"));
                    assertEquals("ai-summary-service", body.get("service"));
                    assertEquals("AI 요약 서비스가 혼잡합니다. 잠시 후 다시 시도해주세요.",
                            body.get("message"));
                    assertEquals(60, body.get("retryAfterSeconds"));
                    assertNotNull(body.get("timestamp"));
                })
                .verifyComplete();
    }

    // ── Search fallback ─────────────────────────────────────────

    @Test
    void searchFallback_returns503WithRetryAfter15() {
        StepVerifier.create(controller.searchFallback())
                .assertNext(response -> {
                    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
                    assertEquals("15", response.getHeaders().getFirst("Retry-After"));

                    Map<String, Object> body = response.getBody();
                    assertNotNull(body);
                    assertEquals(503, body.get("status"));
                    assertEquals("SERVICE_UNAVAILABLE", body.get("code"));
                    assertEquals("search-service", body.get("service"));
                    assertEquals("검색 서비스가 일시적으로 불안정합니다.", body.get("message"));
                    assertEquals(15, body.get("retryAfterSeconds"));
                })
                .verifyComplete();
    }

    // ── FHIR fallback ───────────────────────────────────────────

    @Test
    void fhirFallback_returns503WithCorrectService() {
        StepVerifier.create(controller.fhirFallback())
                .assertNext(response -> {
                    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
                    assertEquals("30", response.getHeaders().getFirst("Retry-After"));

                    Map<String, Object> body = response.getBody();
                    assertNotNull(body);
                    assertEquals("fhir-service", body.get("service"));
                    assertEquals("FHIR 서비스가 일시적으로 불안정합니다.", body.get("message"));
                    assertEquals(30, body.get("retryAfterSeconds"));
                })
                .verifyComplete();
    }

    // ── Files fallback ──────────────────────────────────────────

    @Test
    void filesFallback_returns503WithCorrectService() {
        StepVerifier.create(controller.filesFallback())
                .assertNext(response -> {
                    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
                    assertEquals("30", response.getHeaders().getFirst("Retry-After"));

                    Map<String, Object> body = response.getBody();
                    assertNotNull(body);
                    assertEquals("file-service", body.get("service"));
                    assertEquals("파일 서비스가 일시적으로 불안정합니다.", body.get("message"));
                    assertEquals(30, body.get("retryAfterSeconds"));
                })
                .verifyComplete();
    }

    // ── FCM fallback ────────────────────────────────────────────

    @Test
    void fcmFallback_returns503WithCorrectService() {
        StepVerifier.create(controller.fcmFallback())
                .assertNext(response -> {
                    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
                    assertEquals("30", response.getHeaders().getFirst("Retry-After"));

                    Map<String, Object> body = response.getBody();
                    assertNotNull(body);
                    assertEquals("fcm-service", body.get("service"));
                    assertEquals("알림 서비스가 일시적으로 불안정합니다.", body.get("message"));
                    assertEquals(30, body.get("retryAfterSeconds"));
                })
                .verifyComplete();
    }

    // ── Body shape consistency across all fallbacks ──────────────

    @Test
    void allFallbacks_haveConsistentBodyShape() {
        // Verify that every fallback has the same 6 keys
        StepVerifier.create(controller.chatFallback())
                .assertNext(response -> {
                    Map<String, Object> body = response.getBody();
                    assertNotNull(body);
                    assertTrue(body.containsKey("status"));
                    assertTrue(body.containsKey("code"));
                    assertTrue(body.containsKey("service"));
                    assertTrue(body.containsKey("message"));
                    assertTrue(body.containsKey("retryAfterSeconds"));
                    assertTrue(body.containsKey("timestamp"));
                    assertEquals(6, body.size(), "Fallback body should have exactly 6 fields");
                })
                .verifyComplete();
    }
}
