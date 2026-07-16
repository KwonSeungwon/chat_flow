package com.chatflow.chat.service.outbox;

import com.chatflow.chat.entity.OutboxEvent;
import com.chatflow.chat.entity.OutboxEvent.OutboxStatus;
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
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for OutboxPoller covering the claim-send-finalize lifecycle.
 * <p>
 * The poller now claims a batch (PENDING -> PROCESSING + claim_token) before
 * sending to Kafka, so two replicas cannot double-dispatch the same rows.
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
                .status(OutboxStatus.PENDING)
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

    /**
     * Configure transactionTemplate.execute() to run the lambda immediately
     * and return its result (simulates a real transaction boundary).
     */
    private void stubTransactionExecute() {
        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            TransactionCallback<Object> callback = inv.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
    }

    /**
     * Configure transactionTemplate.executeWithoutResult() to run the lambda immediately.
     */
    private void stubTransactionExecuteWithoutResult() {
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<Object> callback =
                    invocation.getArgument(0, java.util.function.Consumer.class);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    // ── tests ────────────────────────────────────────────────────

    @Test
    void claims_batch_before_sending_to_kafka() {
        // given: two pending outbox events
        OutboxEvent e1 = pendingEvent(1L, "chat-messages", "room-1", "{\"msg\":\"hello\"}");
        OutboxEvent e2 = pendingEvent(2L, "chat-messages", "room-2", "{\"msg\":\"world\"}");

        when(outboxEventRepository.findTop50ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING))
                .thenReturn(List.of(e1, e2));

        // claimBatch returns 2 (both claimed)
        stubTransactionExecute();
        when(outboxEventRepository.claimBatch(anyList(), anyString(), any(LocalDateTime.class)))
                .thenReturn(2);

        // After claim, findByClaimTokenAndStatus returns the claimed events
        when(outboxEventRepository.findByClaimTokenAndStatus(anyString(), eq(OutboxStatus.PROCESSING)))
                .thenReturn(List.of(e1, e2));

        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(successFuture());
        stubTransactionExecuteWithoutResult();

        // when
        outboxPoller.pollOutbox();

        // then: claimBatch was called before Kafka send
        verify(outboxEventRepository).claimBatch(anyList(), anyString(), any(LocalDateTime.class));
        verify(kafkaTemplate, times(2)).send(anyString(), anyString(), any());

        // then: markProcessed called with both ids
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> idsCaptor = ArgumentCaptor.forClass(List.class);
        verify(outboxEventRepository).markProcessed(idsCaptor.capture(), any(LocalDateTime.class));
        assertThat(idsCaptor.getValue()).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void does_nothing_when_no_pending_events() {
        // given
        when(outboxEventRepository.findTop50ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING))
                .thenReturn(Collections.emptyList());

        // when
        outboxPoller.pollOutbox();

        // then: no claim, no Kafka send, no mark
        verify(outboxEventRepository, never()).claimBatch(anyList(), anyString(), any());
        verifyNoInteractions(kafkaTemplate);
        verify(outboxEventRepository, never()).markProcessed(any(), any());
    }

    @Test
    void does_nothing_when_claimed_zero() {
        // given: events exist but another replica already claimed them
        OutboxEvent e1 = pendingEvent(1L, "chat-messages", "room-1", "{\"msg\":\"hello\"}");

        when(outboxEventRepository.findTop50ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING))
                .thenReturn(List.of(e1));

        stubTransactionExecute();
        when(outboxEventRepository.claimBatch(anyList(), anyString(), any(LocalDateTime.class)))
                .thenReturn(0);

        // when
        outboxPoller.pollOutbox();

        // then: no Kafka send, no finalize
        verifyNoInteractions(kafkaTemplate);
        verify(outboxEventRepository, never()).markProcessed(any(), any());
        verify(outboxEventRepository, never()).resetToPending(any());
        verify(outboxEventRepository, never()).markFailed(any(), any());
    }

    @Test
    void sends_only_claimed_events_not_all_candidates() {
        // given: 3 candidates but only 1 claimed (partial claim scenario)
        OutboxEvent e1 = pendingEvent(1L, "chat-messages", "room-1", "{\"msg\":\"hello\"}");
        OutboxEvent e2 = pendingEvent(2L, "chat-messages", "room-2", "{\"msg\":\"world\"}");
        OutboxEvent e3 = pendingEvent(3L, "chat-messages", "room-3", "{\"msg\":\"foo\"}");

        when(outboxEventRepository.findTop50ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING))
                .thenReturn(List.of(e1, e2, e3));

        stubTransactionExecute();
        // Only 1 out of 3 claimed (the other 2 were snagged by another replica)
        when(outboxEventRepository.claimBatch(anyList(), anyString(), any(LocalDateTime.class)))
                .thenReturn(1);

        // findByClaimTokenAndStatus returns only e1
        when(outboxEventRepository.findByClaimTokenAndStatus(anyString(), eq(OutboxStatus.PROCESSING)))
                .thenReturn(List.of(e1));

        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(successFuture());
        stubTransactionExecuteWithoutResult();

        // when
        outboxPoller.pollOutbox();

        // then: only 1 Kafka send (not 3)
        verify(kafkaTemplate, times(1)).send(anyString(), anyString(), any());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> idsCaptor = ArgumentCaptor.forClass(List.class);
        verify(outboxEventRepository).markProcessed(idsCaptor.capture(), any(LocalDateTime.class));
        assertThat(idsCaptor.getValue()).containsExactly(1L);
    }

    @Test
    void failure_under_cap_resets_to_pending() {
        // given: one claimed event with retryCount=0, Kafka send fails
        OutboxEvent e1 = pendingEvent(1L, "chat-messages", "room-1", "{\"msg\":\"hello\"}", 0);

        when(outboxEventRepository.findTop50ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING))
                .thenReturn(List.of(e1));

        stubTransactionExecute();
        when(outboxEventRepository.claimBatch(anyList(), anyString(), any(LocalDateTime.class)))
                .thenReturn(1);
        when(outboxEventRepository.findByClaimTokenAndStatus(anyString(), eq(OutboxStatus.PROCESSING)))
                .thenReturn(List.of(e1));
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(failedFuture());
        stubTransactionExecuteWithoutResult();

        // when
        outboxPoller.pollOutbox();

        // then: markProcessed never called
        verify(outboxEventRepository, never()).markProcessed(any(), any());

        // then: resetToPending called (not incrementRetry — that method is removed)
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> idsCaptor = ArgumentCaptor.forClass(List.class);
        verify(outboxEventRepository).resetToPending(idsCaptor.capture());
        assertThat(idsCaptor.getValue()).containsExactly(1L);

        // then: markFailed NOT called
        verify(outboxEventRepository, never()).markFailed(any(), any());
    }

    @Test
    void failure_at_cap_marks_failed() {
        // given: one claimed event at retryCount=9 (MAX_RETRIES - 1), Kafka send fails
        OutboxEvent e1 = pendingEvent(1L, "chat-messages", "room-1", "{\"msg\":\"poison\"}", 9);

        when(outboxEventRepository.findTop50ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING))
                .thenReturn(List.of(e1));

        stubTransactionExecute();
        when(outboxEventRepository.claimBatch(anyList(), anyString(), any(LocalDateTime.class)))
                .thenReturn(1);
        when(outboxEventRepository.findByClaimTokenAndStatus(anyString(), eq(OutboxStatus.PROCESSING)))
                .thenReturn(List.of(e1));
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(failedFuture());
        stubTransactionExecuteWithoutResult();

        // when
        outboxPoller.pollOutbox();

        // then: markFailed called
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> failedIdsCaptor = ArgumentCaptor.forClass(List.class);
        verify(outboxEventRepository).markFailed(failedIdsCaptor.capture(), any(LocalDateTime.class));
        assertThat(failedIdsCaptor.getValue()).containsExactly(1L);

        // then: resetToPending NOT called
        verify(outboxEventRepository, never()).resetToPending(any());

        // then: markProcessed NOT called
        verify(outboxEventRepository, never()).markProcessed(any(), any());
    }

    @Test
    void mixed_batch_succeeds_and_fails_with_correct_partitioning() {
        // given: three claimed events — e1 succeeds, e2 fails (retry=0), e3 fails (retry=9, poison)
        OutboxEvent e1 = pendingEvent(1L, "chat-messages", "room-1", "{\"msg\":\"ok\"}", 0);
        OutboxEvent e2 = pendingEvent(2L, "chat-messages", "room-2", "{\"msg\":\"retry\"}", 0);
        OutboxEvent e3 = pendingEvent(3L, "chat-messages", "room-3", "{\"msg\":\"poison\"}", 9);

        when(outboxEventRepository.findTop50ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING))
                .thenReturn(List.of(e1, e2, e3));

        stubTransactionExecute();
        when(outboxEventRepository.claimBatch(anyList(), anyString(), any(LocalDateTime.class)))
                .thenReturn(3);
        when(outboxEventRepository.findByClaimTokenAndStatus(anyString(), eq(OutboxStatus.PROCESSING)))
                .thenReturn(List.of(e1, e2, e3));

        // e1 -> success, e2 -> fail, e3 -> fail
        when(kafkaTemplate.send(eq("chat-messages"), eq("room-1"), any())).thenReturn(successFuture());
        when(kafkaTemplate.send(eq("chat-messages"), eq("room-2"), any())).thenReturn(failedFuture());
        when(kafkaTemplate.send(eq("chat-messages"), eq("room-3"), any())).thenReturn(failedFuture());

        stubTransactionExecuteWithoutResult();

        // when
        outboxPoller.pollOutbox();

        // then: markProcessed called with e1's id
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> processedCaptor = ArgumentCaptor.forClass(List.class);
        verify(outboxEventRepository).markProcessed(processedCaptor.capture(), any(LocalDateTime.class));
        assertThat(processedCaptor.getValue()).containsExactly(1L);

        // then: resetToPending called with e2's id (under cap)
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> retryCaptor = ArgumentCaptor.forClass(List.class);
        verify(outboxEventRepository).resetToPending(retryCaptor.capture());
        assertThat(retryCaptor.getValue()).containsExactly(2L);

        // then: markFailed called with e3's id (at cap)
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> failedCaptor = ArgumentCaptor.forClass(List.class);
        verify(outboxEventRepository).markFailed(failedCaptor.capture(), any(LocalDateTime.class));
        assertThat(failedCaptor.getValue()).containsExactly(3L);
    }

    @Test
    void reap_stale_claims_resets_processing_rows() {
        stubTransactionExecuteWithoutResult();
        when(outboxEventRepository.resetStaleProcessing(any(LocalDateTime.class))).thenReturn(3);

        // when
        outboxPoller.reapStaleClaims();

        // then: resetStaleProcessing called with a cutoff ~2 minutes ago
        verify(outboxEventRepository).resetStaleProcessing(any(LocalDateTime.class));
    }
}
