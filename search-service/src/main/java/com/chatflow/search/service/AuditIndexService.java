package com.chatflow.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import com.chatflow.common.dto.AuditEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditIndexService {

    private static final String AUDIT_INDEX = "audit_logs";

    private final ElasticsearchClient elasticsearchClient;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "audit-events")
    public void handleAuditEvent(String messageJson) {
        AuditEvent event;
        try {
            event = objectMapper.readValue(messageJson, AuditEvent.class);
        } catch (JsonProcessingException e) {
            log.error("AuditEvent 역직렬화 실패", e);
            return;
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> doc = objectMapper.convertValue(event, Map.class);
            IndexRequest<Map<String, Object>> request = IndexRequest.of(i -> i
                    .index(AUDIT_INDEX)
                    .id(event.getEventId())
                    .document(doc)
            );
            elasticsearchClient.index(request);
            log.debug("감사 로그 인덱싱 완료: eventId={}, eventType={}", event.getEventId(), event.getEventType());
        } catch (Exception e) {
            log.error("감사 로그 ES 인덱싱 실패: eventId={}", event.getEventId(), e);
        }
    }
}
