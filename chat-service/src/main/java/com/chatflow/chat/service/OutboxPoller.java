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
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPoller {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    // TODO(human): 폴링 전략을 구현하세요.
    // 현재 fixedDelay = 200ms, 배치 크기 50입니다.
    // 고려사항:
    // - 200ms는 실시간 채팅에 적절한 지연인가? (100ms로 줄이면 DB 부하 증가)
    // - 메시지가 없을 때도 200ms마다 쿼리가 실행됩니다 → adaptive polling 고려
    // - 배치 크기 50은 적절한가? (burst traffic에서의 처리량 vs DB 트랜잭션 크기)

    @Scheduled(fixedDelay = 200)
    public void pollOutbox() {
        List<OutboxEvent> pendingEvents =
                outboxEventRepository.findTop50ByStatusOrderByCreatedAtAsc(OutboxEvent.OutboxStatus.PENDING);

        for (OutboxEvent event : pendingEvents) {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    OutboxEvent fresh = outboxEventRepository.findById(event.getId())
                            .orElse(null);
                    if (fresh == null || fresh.getStatus() != OutboxEvent.OutboxStatus.PENDING) {
                        return;
                    }

                    try {
                        Object payloadObj = objectMapper.readValue(fresh.getPayload(), Object.class);
                        kafkaTemplate.send(fresh.getTopic(), fresh.getPartitionKey(), payloadObj);
                    } catch (Exception e) {
                        throw new RuntimeException("Kafka 전송 실패", e);
                    }

                    fresh.setStatus(OutboxEvent.OutboxStatus.PROCESSED);
                    fresh.setProcessedAt(LocalDateTime.now());
                    outboxEventRepository.save(fresh);
                });
            } catch (ObjectOptimisticLockingFailureException e) {
                log.debug("Outbox event {} already processed by another instance", event.getId());
            } catch (Exception e) {
                log.error("Failed to process outbox event: id={}", event.getId(), e);
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
