package com.chatflow.chat.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis 연결 상태를 추적하는 경량 Circuit Breaker.
 * 연속 실패 3회 시 30초간 Redis 호출을 스킵하여
 * timeout으로 인한 스레드 대기를 방지한다.
 */
@Slf4j
@Component
public class RedisHealthTracker {

    private static final int FAILURE_THRESHOLD = 3;
    private static final long CIRCUIT_OPEN_DURATION_MS = 30_000;

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong circuitOpenUntil = new AtomicLong(0);

    public boolean isCircuitOpen() {
        return System.currentTimeMillis() < circuitOpenUntil.get();
    }

    public void recordSuccess() {
        if (consecutiveFailures.get() > 0) {
            consecutiveFailures.set(0);
            log.info("Redis circuit breaker CLOSED — connection recovered");
        }
    }

    public void recordFailure(Exception e) {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= FAILURE_THRESHOLD) {
            long openUntil = System.currentTimeMillis() + CIRCUIT_OPEN_DURATION_MS;
            circuitOpenUntil.set(openUntil);
            log.warn("Redis circuit breaker OPEN — {} consecutive failures, skipping for {}s: {}",
                    failures, CIRCUIT_OPEN_DURATION_MS / 1000, e.getMessage());
        } else {
            log.warn("Redis failure ({}/{}): {}", failures, FAILURE_THRESHOLD, e.getMessage());
        }
    }
}
