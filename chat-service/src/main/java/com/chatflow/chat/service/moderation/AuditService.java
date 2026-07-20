package com.chatflow.chat.service.moderation;

import com.chatflow.chat.entity.OutboxEvent;
import com.chatflow.chat.repository.OutboxEventRepository;
import com.chatflow.common.dto.AuditEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private static final String AUDIT_TOPIC = "audit-events";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public void logAccess(String userId, String username, String roomId, String eventType) {
        AuditEvent event = AuditEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType(eventType)
                .userId(userId)
                .username(username)
                .resourceId(roomId)
                .roomId(roomId)
                .timestamp(LocalDateTime.now())
                .build();

        try {
            String payload = objectMapper.writeValueAsString(event);
            // Delivery semantics: at-least-once (durable outbox + poller retry).
            // Previously fire-and-forget (at-most-once). A rare reaper re-send may
            // duplicate, which is acceptable for audit logs.
            OutboxEvent outbox = OutboxEvent.builder()
                    .aggregateType("AuditEvent")
                    .aggregateId(event.getEventId())
                    .eventType(eventType)
                    .topic(AUDIT_TOPIC)
                    .partitionKey(roomId)
                    .payload(payload)
                    .build();
            outboxEventRepository.save(outbox);
            log.debug("감사 로그 발행: eventType={}, userId={}, roomId={}", eventType, userId, roomId);
        } catch (JsonProcessingException e) {
            log.error("감사 이벤트 직렬화 실패: eventType={}, roomId={}", eventType, roomId, e);
        }
    }
}
