package com.chatflow.chat.service.outbox;

import com.chatflow.chat.entity.OutboxEvent;
import com.chatflow.chat.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for OutboxPoller covering the poll-send-mark lifecycle.
 */
@ExtendWith(MockitoExtension.class)
class OutboxPollerTest {

    @Mock private OutboxEventRepository outboxEventRepository;
    @Mock private KafkaTemplate<String, Object> kafkaTemplate;
    @Mock private TransactionTemplate transactionTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private OutboxPoller outboxPoller;

    @BeforeEach
    void setUp() {
        outboxPoller = new OutboxPoller(
                outboxEventRepository, kafkaTemplate, transactionTemplate,
                objectMapper, new SimpleMeterRegistry());
    }

    // ── helpers ──────────────────────────────────────────────────

    private static OutboxEvent pendingEvent(Long id, String topic, String key, String payload) {
        return OutboxEvent.builder()
                .id(id)
                .topic(topic)
                .partitionKey(key)
                .payload(payload)
                .status(OutboxEvent.OutboxStatus.PENDING)
                .aggregateType("ChatMessage")
                .aggregateId("msg-" + id)
                .eventType("MESSAGE_SENT")
                .createdAt(LocalDateTime.of(2026, 1, 1, 12, 0))
                .build();
    }

    private static CompletableFuture<SendResult<String, Object>> successFuture() {
        RecordMetadata metadata = new RecordMetadata(
                new TopicPartition("t", 0), 0, 0, 0L, 0, 0);
        SendResult<String, Object> result = new SendResult<>(
                new ProducerRecord<>("t", "k", "v"), metadata);
        return CompletableFuture.completedFuture(result);
    }

    private static CompletableFuture<SendResult<String, Object>> failedFuture() {
        CompletableFuture<SendResult<String, Object>> f = new CompletableFuture<>();
        f.completeExceptionally(new RuntimeException("Kafka broker unavailable"));
        return f;
    }

    // ── tests ────────────────────────────────────────────────────

    @Test
    void publishes_pending_events_then_marks_them_processed() {
        // given: two pending outbox events
        OutboxEvent e1 = pendingEvent(1L, "chat-messages", "room-1", "{\"msg\":\"hello\"}");
        OutboxEvent e2 = pendingEvent(2L, "chat-messages", "room-2", "{\"msg\":\"world\"}");

        when(outboxEventRepository.findTop50ByStatusOrderByCreatedAtAsc(OutboxEvent.OutboxStatus.PENDING))
                .thenReturn(List.of(e1, e2));
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(successFuture());

        // transactionTemplate.executeWithoutResult delegates immediately
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<Object> callback =
                    invocation.getArgument(0, java.util.function.Consumer.class);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        // when
        outboxPoller.pollOutbox();

        // then: both events sent to Kafka
        @SuppressWarnings("unchecked")
        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate, times(2)).send(topicCaptor.capture(), keyCaptor.capture(), any());

        assertThat(topicCaptor.getAllValues()).containsExactly("chat-messages", "chat-messages");
        assertThat(keyCaptor.getAllValues()).containsExactly("room-1", "room-2");

        // then: markProcessed called with both ids
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> idsCaptor = ArgumentCaptor.forClass(List.class);
        verify(outboxEventRepository).markProcessed(idsCaptor.capture(), any(LocalDateTime.class));
        assertThat(idsCaptor.getValue()).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void does_nothing_when_no_pending_events() {
        // given
        when(outboxEventRepository.findTop50ByStatusOrderByCreatedAtAsc(OutboxEvent.OutboxStatus.PENDING))
                .thenReturn(Collections.emptyList());

        // when
        outboxPoller.pollOutbox();

        // then: no Kafka send, no mark
        verifyNoInteractions(kafkaTemplate);
        verify(outboxEventRepository, never()).markProcessed(any(), any());
    }

    @Test
    void keeps_event_pending_when_kafka_send_throws() {
        // given: one event, Kafka send returns a failed future
        OutboxEvent e1 = pendingEvent(1L, "chat-messages", "room-1", "{\"msg\":\"hello\"}");

        when(outboxEventRepository.findTop50ByStatusOrderByCreatedAtAsc(OutboxEvent.OutboxStatus.PENDING))
                .thenReturn(List.of(e1));
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(failedFuture());

        // when
        outboxPoller.pollOutbox();

        // then: markProcessed never called — event stays PENDING for next poll
        verify(outboxEventRepository, never()).markProcessed(any(), any());
        verifyNoInteractions(transactionTemplate);
    }
}
