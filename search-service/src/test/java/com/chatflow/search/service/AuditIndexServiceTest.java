package com.chatflow.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import com.chatflow.common.dto.AuditEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AuditIndexService}.
 *
 * <p>Uses a REAL {@link ObjectMapper} (with {@link JavaTimeModule} for
 * {@link LocalDateTime} support) and a mocked {@link ElasticsearchClient}.
 * The service is instantiated directly via constructor injection to avoid
 * loading a Spring context.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuditIndexService")
class AuditIndexServiceTest {

    @Mock
    private ElasticsearchClient elasticsearchClient;

    @Captor
    private ArgumentCaptor<IndexRequest<Map<String, Object>>> requestCaptor;

    private ObjectMapper objectMapper;
    private AuditIndexService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        service = new AuditIndexService(elasticsearchClient, objectMapper);
    }

    // ── valid audit event indexing ──────────────────────────────────────

    @Nested
    @DisplayName("valid audit event")
    class ValidAuditEventTests {

        @Test
        @DisplayName("indexes by eventId into audit_logs with correct document fields")
        @SuppressWarnings("unchecked")
        void indexesByEventId() throws Exception {
            // given
            String eventId = "evt-abc-123";
            AuditEvent event = AuditEvent.builder()
                    .eventId(eventId)
                    .eventType(AuditEvent.MESSAGE_READ)
                    .userId("user-42")
                    .username("testuser")
                    .roomId("room-7")
                    .timestamp(LocalDateTime.of(2026, 7, 4, 14, 30, 0))
                    .build();
            String json = objectMapper.writeValueAsString(event);

            stubIndexResponse();

            // when
            service.handleAuditEvent(json);

            // then
            verify(elasticsearchClient).index(requestCaptor.capture());
            IndexRequest<Map<String, Object>> captured = requestCaptor.getValue();

            assertThat(captured.index()).isEqualTo("audit_logs");
            assertThat(captured.id()).isEqualTo(eventId);

            Map<String, Object> doc = captured.document();
            assertThat(doc).isNotNull();
            assertThat(doc.get("eventType")).isEqualTo(AuditEvent.MESSAGE_READ);
            assertThat(doc.get("userId")).isEqualTo("user-42");
            assertThat(doc.get("roomId")).isEqualTo("room-7");
            assertThat(doc.get("eventId")).isEqualTo(eventId);
        }
    }

    // ── idempotency ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("idempotency")
    class IdempotencyTests {

        @Test
        @DisplayName("same event delivered twice produces two index calls with identical doc id (upsert semantics)")
        @SuppressWarnings("unchecked")
        void sameEventTwiceUsesSameDocId() throws Exception {
            // given
            String eventId = "evt-idempotent-001";
            AuditEvent event = AuditEvent.builder()
                    .eventId(eventId)
                    .eventType(AuditEvent.ROOM_JOIN)
                    .userId("user-99")
                    .roomId("room-3")
                    .timestamp(LocalDateTime.of(2026, 7, 4, 10, 0, 0))
                    .build();
            String json = objectMapper.writeValueAsString(event);

            stubIndexResponse();

            // when  -- simulate at-least-once Kafka redelivery
            service.handleAuditEvent(json);
            service.handleAuditEvent(json);

            // then  -- both calls use the same document id = idempotent upsert
            verify(elasticsearchClient, times(2)).index(requestCaptor.capture());
            assertThat(requestCaptor.getAllValues())
                    .extracting(IndexRequest::id)
                    .containsExactly(eventId, eventId);
        }
    }

    // ── malformed JSON ──────────────────────────────────────────────────

    @Nested
    @DisplayName("malformed JSON")
    class MalformedJsonTests {

        @Test
        @DisplayName("malformed JSON propagates (container dead-letters it) and never calls index")
        void malformedJsonPropagates() {
            assertThatThrownBy(() -> service.handleAuditEvent("{not json"))
                    .isInstanceOf(JsonProcessingException.class);

            verifyNoInteractions(elasticsearchClient);
        }
    }

    // ── ES error propagation ────────────────────────────────────────────

    @Nested
    @DisplayName("ES error handling")
    class EsErrorHandlingTests {

        @Test
        @DisplayName("IOException from ES index() propagates so the shared error handler retries/dead-letters")
        @SuppressWarnings("unchecked")
        void esFailure_propagates() throws Exception {
            // given
            AuditEvent event = AuditEvent.builder()
                    .eventId("evt-fail-001")
                    .eventType(AuditEvent.MESSAGE_SEARCH)
                    .userId("user-1")
                    .roomId("room-1")
                    .timestamp(LocalDateTime.of(2026, 1, 1, 0, 0, 0))
                    .build();
            String json = objectMapper.writeValueAsString(event);

            when(elasticsearchClient.index(any(IndexRequest.class)))
                    .thenThrow(new IOException("ES cluster unavailable"));

            // when / then -- exception reaches the listener container
            assertThatThrownBy(() -> service.handleAuditEvent(json))
                    .isInstanceOf(IOException.class)
                    .hasMessage("ES cluster unavailable");

            verify(elasticsearchClient).index(any(IndexRequest.class));
        }

        @Test
        @DisplayName("RuntimeException from ES index() also propagates")
        @SuppressWarnings("unchecked")
        void esRuntimeException_propagates() throws Exception {
            // given
            AuditEvent event = AuditEvent.builder()
                    .eventId("evt-fail-002")
                    .eventType(AuditEvent.ROOM_HIDDEN)
                    .userId("user-2")
                    .roomId("room-2")
                    .timestamp(LocalDateTime.of(2026, 6, 15, 12, 0, 0))
                    .build();
            String json = objectMapper.writeValueAsString(event);

            when(elasticsearchClient.index(any(IndexRequest.class)))
                    .thenThrow(new RuntimeException("unexpected failure"));

            // when / then
            assertThatThrownBy(() -> service.handleAuditEvent(json))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("unexpected failure");
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void stubIndexResponse() throws IOException {
        IndexResponse mockResponse = mock(IndexResponse.class);
        when(elasticsearchClient.index(any(IndexRequest.class))).thenReturn(mockResponse);
    }
}
