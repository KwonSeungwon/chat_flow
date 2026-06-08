package com.chatflow.chat.service.outbox;

import com.chatflow.chat.entity.OutboxEvent;
import com.chatflow.chat.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Slf4j
@Component
public class OutboxPoller {

    private static final int MAX_RETRIES = 10;

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final Timer pollTimer;

    public OutboxPoller(OutboxEventRepository outboxEventRepository,
                        KafkaTemplate<String, Object> kafkaTemplate,
                        TransactionTemplate transactionTemplate,
                        ObjectMapper objectMapper,
                        MeterRegistry registry) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.pollTimer = Timer.builder("chatflow.outbox.poll.duration")
                .description("Outbox poll cycle duration")
                .register(registry);
        registry.gauge("chatflow.outbox.pending", outboxEventRepository,
                repo -> repo.findTop50ByStatusOrderByCreatedAtAsc(OutboxEvent.OutboxStatus.PENDING).size());
    }

    @Scheduled(fixedDelay = 200)
    public void pollOutbox() {
        pollTimer.record(() -> doPoll());
    }

    private void doPoll() {
        List<OutboxEvent> pendingEvents =
                outboxEventRepository.findTop50ByStatusOrderByCreatedAtAsc(OutboxEvent.OutboxStatus.PENDING);

        if (pendingEvents.isEmpty()) return;

        // Phase 1: 모든 이벤트를 병렬로 Kafka 전송, future 수집
        List<CompletableFuture<OutboxEvent>> futures = new ArrayList<>();
        for (OutboxEvent event : pendingEvents) {
            try {
                Object payloadObj = objectMapper.readValue(event.getPayload(), Object.class);
                CompletableFuture<OutboxEvent> future = kafkaTemplate
                        .send(event.getTopic(), event.getPartitionKey(), payloadObj)
                        .thenApply(result -> event)
                        .toCompletableFuture();
                futures.add(future);
            } catch (Exception e) {
                log.error("Failed to parse outbox event payload: id={}", event.getId(), e);
            }
        }

        // Phase 2: 모든 전송 완료 대기 (30초 타임아웃)
        List<OutboxEvent> succeeded = new ArrayList<>();
        for (CompletableFuture<OutboxEvent> future : futures) {
            try {
                OutboxEvent sent = future.get(30, java.util.concurrent.TimeUnit.SECONDS);
                succeeded.add(sent);
            } catch (Exception e) {
                log.error("Kafka send failed for outbox event: {}", e.getMessage());
            }
        }

        // Phase 3: 성공한 이벤트를 단일 JPQL로 일괄 PROCESSED 처리
        if (!succeeded.isEmpty()) {
            try {
                List<Long> ids = succeeded.stream().map(OutboxEvent::getId).toList();
                transactionTemplate.executeWithoutResult(status -> {
                    int updated = outboxEventRepository.markProcessed(ids, LocalDateTime.now());
                    log.info("Outbox batch processed: {}/{} events", updated, pendingEvents.size());
                });
            } catch (Exception e) {
                log.error("Failed to update outbox status for batch", e);
            }
        }

        // Phase 4: 실패한 이벤트 — retry 카운트 증가 또는 FAILED 마킹
        Set<Long> succeededIds = succeeded.stream().map(OutboxEvent::getId).collect(Collectors.toSet());
        List<OutboxEvent> failedEvents = pendingEvents.stream()
                .filter(e -> !succeededIds.contains(e.getId()))
                .toList();

        if (!failedEvents.isEmpty()) {
            List<Long> toFail = new ArrayList<>();
            List<Long> toRetry = new ArrayList<>();

            for (OutboxEvent event : failedEvents) {
                if (event.getRetryCount() + 1 >= MAX_RETRIES) {
                    toFail.add(event.getId());
                } else {
                    toRetry.add(event.getId());
                }
            }

            try {
                transactionTemplate.executeWithoutResult(status -> {
                    if (!toFail.isEmpty()) {
                        outboxEventRepository.markFailed(toFail, LocalDateTime.now());
                        log.warn("Outbox events marked FAILED (poison-pill cap reached): ids={}", toFail);
                    }
                    if (!toRetry.isEmpty()) {
                        outboxEventRepository.incrementRetry(toRetry);
                        log.debug("Outbox events retry incremented: ids={}", toRetry);
                    }
                });
            } catch (Exception e) {
                log.error("Failed to update retry/failed status for outbox events", e);
            }
        }
    }

    @Scheduled(fixedRate = 3600000)
    public void cleanupProcessedEvents() {
        transactionTemplate.executeWithoutResult(status -> {
            int deleted = outboxEventRepository.deleteProcessedBefore(
                    LocalDateTime.now().minusHours(24));
            if (deleted > 0) {
                log.info("Cleaned up {} processed outbox events", deleted);
            }
        });
    }
}
