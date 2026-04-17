package com.chatflow.gateway.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 서킷 브레이커 fallback 엔드포인트.
 * 각 백엔드 서비스가 장애/타임아웃/Slow-call 로 열린 상태일 때 이 엔드포인트로 라우팅된다.
 *
 * 응답: 503 Service Unavailable + Retry-After 헤더 + JSON 본문
 */
@Slf4j
@RestController
@RequestMapping("/api/fallback")
public class FallbackController {

    @RequestMapping("/chat")
    public Mono<ResponseEntity<Map<String, Object>>> chatFallback() {
        return buildFallback("chat-service", "채팅 서비스가 일시적으로 불안정합니다. 잠시 후 다시 시도해주세요.", 30);
    }

    @RequestMapping("/ai-summary")
    public Mono<ResponseEntity<Map<String, Object>>> aiSummaryFallback() {
        return buildFallback("ai-summary-service", "AI 요약 서비스가 혼잡합니다. 잠시 후 다시 시도해주세요.", 60);
    }

    @RequestMapping("/search")
    public Mono<ResponseEntity<Map<String, Object>>> searchFallback() {
        return buildFallback("search-service", "검색 서비스가 일시적으로 불안정합니다.", 15);
    }

    @RequestMapping("/fhir")
    public Mono<ResponseEntity<Map<String, Object>>> fhirFallback() {
        return buildFallback("fhir-service", "FHIR 서비스가 일시적으로 불안정합니다.", 30);
    }

    @RequestMapping("/files")
    public Mono<ResponseEntity<Map<String, Object>>> filesFallback() {
        return buildFallback("file-service", "파일 서비스가 일시적으로 불안정합니다.", 30);
    }

    @RequestMapping("/fcm")
    public Mono<ResponseEntity<Map<String, Object>>> fcmFallback() {
        return buildFallback("fcm-service", "알림 서비스가 일시적으로 불안정합니다.", 30);
    }

    private Mono<ResponseEntity<Map<String, Object>>> buildFallback(String service, String message, int retryAfterSeconds) {
        log.warn("Circuit breaker open: {} — 요청을 fallback으로 라우팅 (retry-after {}s)", service, retryAfterSeconds);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", 503);
        body.put("code", "SERVICE_UNAVAILABLE");
        body.put("service", service);
        body.put("message", message);
        body.put("retryAfterSeconds", retryAfterSeconds);
        body.put("timestamp", LocalDateTime.now().toString());
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", String.valueOf(retryAfterSeconds))
                .body(body));
    }
}
