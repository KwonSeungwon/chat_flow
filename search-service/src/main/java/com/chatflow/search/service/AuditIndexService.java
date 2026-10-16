package com.chatflow.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import com.chatflow.common.dto.AuditEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditIndexService {

    private static final String AUDIT_INDEX = "audit_logs";

    private final ElasticsearchClient elasticsearchClient;
    private final ObjectMapper objectMapper;

    /**
     * Indexes one audit event. Failures (malformed payload or ES error) are
     * propagated so the shared {@code DefaultErrorHandler} retries and finally
     * dead-letters the record instead of the offset being committed silently.
     */
    @KafkaListener(topics = "audit-events")
    public void handleAuditEvent(String messageJson) throws IOException {
        AuditEvent event = objectMapper.readValue(messageJson, AuditEvent.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> doc = objectMapper.convertValue(event, Map.class);
        IndexRequest<Map<String, Object>> request = IndexRequest.of(i -> i
                .index(AUDIT_INDEX)
                .id(event.getEventId())
                .document(doc)
        );
        elasticsearchClient.index(request);
        log.debug("감사 로그 인덱싱 완료: eventId={}, eventType={}", event.getEventId(), event.getEventType());
    }
}
