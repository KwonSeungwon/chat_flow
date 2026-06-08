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
        return pendingEvent(id, topic, key, payload, 0);
    }

    private static OutboxEvent pendingEvent(Long id, String topic, String key, String payload, int retryCount) {
        return OutboxEvent.builder()
                .id(id)
                .topic(topic)
                .partitionKey(key)
                .payload(payload)
                .status(OutboxEvent.OutboxStatus.PENDING)
                .aggregateType("ChatMessage")
                .aggregateId("msg-" + id)
                .eventType("MESSAGE_SENT")
                .retryCount(retryCount)
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
    void increments_retry_when_kafka_send_fails_and_under_max_retries() {
        // given: one event with retryCount=0, Kafka send returns a failed future
        OutboxEvent e1 = pendingEvent(1L, "chat-messages", "room-1", "{\"msg\":\"hello\"}", 0);

        when(outboxEventRepository.findTop50ByStatusOrderByCreatedAtAsc(OutboxEvent.OutboxStatus.PENDING))
                .thenReturn(List.of(e1));
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(failedFuture());

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

        // then: markProcessed never called — event not succeeded
        verify(outboxEventRepository, never()).markProcessed(any(), any());

        // then: incrementRetry called with that event's id
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> idsCaptor = ArgumentCaptor.forClass(List.class);
        verify(outboxEventRepository).incrementRetry(idsCaptor.capture());
        assertThat(idsCaptor.getValue()).containsExactly(1L);

        // then: markFailed NOT called
        verify(outboxEventRepository, never()).markFailed(any(), any());
    }

    @Test
    void event_exceeding_max_retries_is_marked_failed_not_retried_forever() {
        // given: one event already at retryCount=9 (MAX_RETRIES - 1), Kafka send fails
        OutboxEvent e1 = pendingEvent(1L, "chat-messages", "room-1", "{\"msg\":\"poison\"}", 9);

        when(outboxEventRepository.findTop50ByStatusOrderByCreatedAtAsc(OutboxEvent.OutboxStatus.PENDING))
                .thenReturn(List.of(e1));
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(failedFuture());

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

        // then: markFailed called with that event's id
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> failedIdsCaptor = ArgumentCaptor.forClass(List.class);
        verify(outboxEventRepository).markFailed(failedIdsCaptor.capture(), any(LocalDateTime.class));
        assertThat(failedIdsCaptor.getValue()).containsExactly(1L);

        // then: incrementRetry NOT called for this event
        verify(outboxEventRepository, never()).incrementRetry(any());

        // then: markProcessed NOT called
        verify(outboxEventRepository, never()).markProcessed(any(), any());
    }

    @Test
    void event_under_max_retries_is_incremented_not_failed() {
        // given: one event with retryCount=5, Kafka send fails
        OutboxEvent e1 = pendingEvent(1L, "chat-messages", "room-1", "{\"msg\":\"retry\"}", 5);

        when(outboxEventRepository.findTop50ByStatusOrderByCreatedAtAsc(OutboxEvent.OutboxStatus.PENDING))
                .thenReturn(List.of(e1));
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(failedFuture());

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

        // then: incrementRetry called
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> idsCaptor = ArgumentCaptor.forClass(List.class);
        verify(outboxEventRepository).incrementRetry(idsCaptor.capture());
        assertThat(idsCaptor.getValue()).containsExactly(1L);

        // then: markFailed NOT called
        verify(outboxEventRepository, never()).markFailed(any(), any());
    }

    @Test
    void mixed_batch_succeeds_and_fails_with_correct_partitioning() {
        // given: three events — e1 succeeds, e2 fails (retryCount=0), e3 fails (retryCount=9, poison)
        OutboxEvent e1 = pendingEvent(1L, "chat-messages", "room-1", "{\"msg\":\"ok\"}", 0);
        OutboxEvent e2 = pendingEvent(2L, "chat-messages", "room-2", "{\"msg\":\"retry\"}", 0);
        OutboxEvent e3 = pendingEvent(3L, "chat-messages", "room-3", "{\"msg\":\"poison\"}", 9);

        when(outboxEventRepository.findTop50ByStatusOrderByCreatedAtAsc(OutboxEvent.OutboxStatus.PENDING))
                .thenReturn(List.of(e1, e2, e3));

        // e1 → success, e2 → fail, e3 → fail
        when(kafkaTemplate.send(eq("chat-messages"), eq("room-1"), any())).thenReturn(successFuture());
        when(kafkaTemplate.send(eq("chat-messages"), eq("room-2"), any())).thenReturn(failedFuture());
        when(kafkaTemplate.send(eq("chat-messages"), eq("room-3"), any())).thenReturn(failedFuture());

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

        // then: markProcessed called with e1's id
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> processedCaptor = ArgumentCaptor.forClass(List.class);
        verify(outboxEventRepository).markProcessed(processedCaptor.capture(), any(LocalDateTime.class));
        assertThat(processedCaptor.getValue()).containsExactly(1L);

        // then: incrementRetry called with e2's id (under cap)
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> retryCaptor = ArgumentCaptor.forClass(List.class);
        verify(outboxEventRepository).incrementRetry(retryCaptor.capture());
        assertThat(retryCaptor.getValue()).containsExactly(2L);

        // then: markFailed called with e3's id (at cap)
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> failedCaptor = ArgumentCaptor.forClass(List.class);
        verify(outboxEventRepository).markFailed(failedCaptor.capture(), any(LocalDateTime.class));
        assertThat(failedCaptor.getValue()).containsExactly(3L);
    }
}
