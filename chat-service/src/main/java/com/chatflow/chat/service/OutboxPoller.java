package com.chatflow.chat.service;

import com.chatflow.chat.entity.OutboxEvent;
import com.chatflow.chat.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPoller {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelay = 200)
    public void pollOutbox() {
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

        // Phase 3: 성공한 이벤트를 한 트랜잭션에서 일괄 PROCESSED 처리
        if (!succeeded.isEmpty()) {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    LocalDateTime now = LocalDateTime.now();
                    for (OutboxEvent event : succeeded) {
                        OutboxEvent fresh = outboxEventRepository.findById(event.getId()).orElse(null);
                        if (fresh != null && fresh.getStatus() == OutboxEvent.OutboxStatus.PENDING) {
                            fresh.setStatus(OutboxEvent.OutboxStatus.PROCESSED);
                            fresh.setProcessedAt(now);
                            outboxEventRepository.save(fresh);
                        }
                    }
                });
                log.info("Outbox batch processed: {}/{} events", succeeded.size(), pendingEvents.size());
            } catch (ObjectOptimisticLockingFailureException e) {
                log.debug("Some outbox events already processed by another instance");
            } catch (Exception e) {
                log.error("Failed to update outbox status for batch", e);
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
