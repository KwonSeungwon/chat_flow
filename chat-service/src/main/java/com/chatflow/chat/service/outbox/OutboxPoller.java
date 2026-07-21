package com.chatflow.chat.service.outbox;

import com.chatflow.chat.entity.OutboxEvent;
import com.chatflow.chat.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
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
import java.util.UUID;
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
    private final Counter reapedCounter;

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
        // A steadily-climbing reaped count signals sends chronically exceeding the
        // 2m cutoff — each reaped row risks an at-least-once duplicate re-send.
        this.reapedCounter = Counter.builder("chatflow.outbox.reaped")
                .description("Stale PROCESSING outbox events reset to PENDING by the reaper")
                .register(registry);
        registry.gauge("chatflow.outbox.pending", outboxEventRepository,
                repo -> repo.countByStatus(OutboxEvent.OutboxStatus.PENDING));
    }

    @Scheduled(fixedDelay = 200)
    public void pollOutbox() {
        pollTimer.record(() -> doPoll());
    }

    void doPoll() {
        // Step 1: Find PENDING candidates
        List<OutboxEvent> candidates =
                outboxEventRepository.findTop50ByStatusOrderByCreatedAtAsc(OutboxEvent.OutboxStatus.PENDING);

        if (candidates.isEmpty()) return;

        // Step 2: Claim the batch atomically (PENDING -> PROCESSING + claim_token)
        String token = UUID.randomUUID().toString();
        List<Long> candidateIds = candidates.stream().map(OutboxEvent::getId).toList();

        Integer claimed = transactionTemplate.execute(status ->
                outboxEventRepository.claimBatch(candidateIds, token, LocalDateTime.now()));

        if (claimed == null || claimed == 0) return;

        // Step 3: Retrieve exactly this poller's claimed rows
        List<OutboxEvent> claimedEvents =
                outboxEventRepository.findByClaimTokenAndStatus(token, OutboxEvent.OutboxStatus.PROCESSING);

        if (claimedEvents.isEmpty()) return;

        // Step 4: Send claimed events to Kafka in parallel
        List<CompletableFuture<OutboxEvent>> futures = new ArrayList<>();
        for (OutboxEvent event : claimedEvents) {
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

        // Step 5: Await all sends (30s timeout)
        List<OutboxEvent> succeeded = new ArrayList<>();
        for (CompletableFuture<OutboxEvent> future : futures) {
            try {
                OutboxEvent sent = future.get(30, java.util.concurrent.TimeUnit.SECONDS);
                succeeded.add(sent);
            } catch (Exception e) {
                log.error("Kafka send failed for outbox event: {}", e.getMessage());
            }
        }

        // Step 6: Finalize — succeeded -> PROCESSED; failed -> resetToPending or FAILED at cap
        Set<Long> succeededIds = succeeded.stream().map(OutboxEvent::getId).collect(Collectors.toSet());

        if (!succeededIds.isEmpty()) {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    int updated = outboxEventRepository.markProcessed(
                            new ArrayList<>(succeededIds), LocalDateTime.now());
                    log.info("Outbox batch processed: {}/{} events", updated, claimedEvents.size());
                });
            } catch (Exception e) {
                log.error("Failed to update outbox status for batch", e);
            }
        }

        List<OutboxEvent> failedEvents = claimedEvents.stream()
                .filter(ev -> !succeededIds.contains(ev.getId()))
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
                        outboxEventRepository.resetToPending(toRetry);
                        log.debug("Outbox events reset to PENDING for retry: ids={}", toRetry);
                    }
                });
            } catch (Exception e) {
                log.error("Failed to update retry/failed status for outbox events", e);
            }
        }
    }

    /**
     * Reaper: recover rows stuck in PROCESSING from a crashed poller instance.
     * Cutoff = 2 minutes (well beyond the 30s Kafka send timeout, so in-flight
     * sends will not be reaped).
     *
     * Note: reset-to-PENDING may re-send an event that was actually delivered
     * right before a crash -- at-least-once; consumers (search-service upsert-by-id,
     * ai-summary) are already idempotent.
     */
    @Scheduled(fixedDelay = 60000)
    public void reapStaleClaims() {
        transactionTemplate.executeWithoutResult(status -> {
            int reset = outboxEventRepository.resetStaleProcessing(
                    LocalDateTime.now().minusMinutes(2));
            if (reset > 0) {
                reapedCounter.increment(reset);
                log.warn("Reaped {} stale PROCESSING outbox events back to PENDING", reset);
            }
        });
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
