package com.chatflow.chat.service;

import com.chatflow.common.dto.AuditEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private static final String AUDIT_TOPIC = "audit-events";

    private final KafkaTemplate<String, Object> kafkaTemplate;
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
            kafkaTemplate.send(AUDIT_TOPIC, roomId, payload);
            log.debug("감사 로그 발행: eventType={}, userId={}, roomId={}", eventType, userId, roomId);
        } catch (JsonProcessingException e) {
            log.error("감사 이벤트 직렬화 실패: eventType={}, roomId={}", eventType, roomId, e);
        }
    }
}
