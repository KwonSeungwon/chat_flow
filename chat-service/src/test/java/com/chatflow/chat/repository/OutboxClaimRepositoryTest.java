package com.chatflow.chat.repository;

import com.chatflow.chat.entity.OutboxEvent;
import com.chatflow.chat.entity.OutboxEvent.OutboxStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @DataJpaTest for the outbox claim/reaper repository methods (V13).
 * <p>
 * Verifies:
 * <ul>
 *   <li>{@code claimBatch}: PENDING -> PROCESSING with claim token</li>
 *   <li>Double-claim prevention: second claim on same ids returns 0</li>
 *   <li>{@code findByClaimTokenAndStatus}: returns exactly the claimed rows</li>
 *   <li>{@code resetToPending}: PROCESSING -> PENDING with retry++ and cleared token</li>
 *   <li>{@code resetStaleProcessing}: resets only rows with claimedAt older than cutoff</li>
 *   <li>{@code markProcessed}: only transitions from PROCESSING</li>
 *   <li>{@code markFailed}: only transitions from PROCESSING</li>
 * </ul>
 */
@DataJpaTest
@ContextConfiguration(classes = RepositoryTestConfig.class)
@ActiveProfiles("test")
class OutboxClaimRepositoryTest {

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 7, 1, 12, 0, 0);

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
    }

    // ---- claimBatch ----

    @Test
    @Transactional
    void claimBatch_flipsPendingToProcessingWithToken() {
        OutboxEvent e1 = outboxEventRepository.save(pendingEvent("topic-a", "key-1"));
        OutboxEvent e2 = outboxEventRepository.save(pendingEvent("topic-a", "key-2"));

        String token = "token-aaa";
        int claimed = outboxEventRepository.claimBatch(
                List.of(e1.getId(), e2.getId()), token, BASE);

        assertThat(claimed).isEqualTo(2);

        // Verify status + token via findByClaimTokenAndStatus
        List<OutboxEvent> found = outboxEventRepository.findByClaimTokenAndStatus(
                token, OutboxStatus.PROCESSING);
        assertThat(found).hasSize(2);
        assertThat(found).extracting(OutboxEvent::getClaimToken).containsOnly(token);
        assertThat(found).extracting(OutboxEvent::getClaimedAt).containsOnly(BASE);
    }

    @Test
    @Transactional
    void claimBatch_secondClaimOnSameIds_returnsZero() {
        OutboxEvent e1 = outboxEventRepository.save(pendingEvent("topic-a", "key-1"));

        // First claim succeeds
        int first = outboxEventRepository.claimBatch(
                List.of(e1.getId()), "token-aaa", BASE);
        assertThat(first).isEqualTo(1);

        // Second claim with a different token returns 0 (already PROCESSING)
        int second = outboxEventRepository.claimBatch(
                List.of(e1.getId()), "token-bbb", BASE.plusSeconds(1));
        assertThat(second).isZero();
    }

    @Test
    @Transactional
    void claimBatch_skipsNonPendingRows() {
        OutboxEvent pending = outboxEventRepository.save(pendingEvent("topic-a", "key-1"));
        OutboxEvent processed = outboxEventRepository.save(
                eventWithStatus("topic-a", "key-2", OutboxStatus.PROCESSED));

        int claimed = outboxEventRepository.claimBatch(
                List.of(pending.getId(), processed.getId()), "token-aaa", BASE);

        assertThat(claimed).isEqualTo(1);

        List<OutboxEvent> found = outboxEventRepository.findByClaimTokenAndStatus(
                "token-aaa", OutboxStatus.PROCESSING);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getId()).isEqualTo(pending.getId());
    }

    // ---- findByClaimTokenAndStatus ----

    @Test
    @Transactional
    void findByClaimTokenAndStatus_returnsOnlyMatchingTokenAndStatus() {
        OutboxEvent e1 = outboxEventRepository.save(pendingEvent("topic-a", "key-1"));
        OutboxEvent e2 = outboxEventRepository.save(pendingEvent("topic-a", "key-2"));
        OutboxEvent e3 = outboxEventRepository.save(pendingEvent("topic-a", "key-3"));

        // Claim e1+e2 with token-aaa, e3 with token-bbb
        outboxEventRepository.claimBatch(List.of(e1.getId(), e2.getId()), "token-aaa", BASE);
        outboxEventRepository.claimBatch(List.of(e3.getId()), "token-bbb", BASE);

        List<OutboxEvent> aaa = outboxEventRepository.findByClaimTokenAndStatus(
                "token-aaa", OutboxStatus.PROCESSING);
        assertThat(aaa).hasSize(2);
        assertThat(aaa).extracting(OutboxEvent::getId)
                .containsExactlyInAnyOrder(e1.getId(), e2.getId());

        List<OutboxEvent> bbb = outboxEventRepository.findByClaimTokenAndStatus(
                "token-bbb", OutboxStatus.PROCESSING);
        assertThat(bbb).hasSize(1);
        assertThat(bbb.get(0).getId()).isEqualTo(e3.getId());
    }

    // ---- resetToPending ----

    @Test
    @Transactional
    void resetToPending_restoresPendingAndIncrementsRetryAndClearsToken() {
        OutboxEvent e1 = outboxEventRepository.save(pendingEvent("topic-a", "key-1"));
        int initialRetry = e1.getRetryCount();

        // Claim it first
        outboxEventRepository.claimBatch(List.of(e1.getId()), "token-aaa", BASE);

        // Reset to PENDING
        int reset = outboxEventRepository.resetToPending(List.of(e1.getId()));
        assertThat(reset).isEqualTo(1);

        // Verify state
        OutboxEvent refreshed = outboxEventRepository.findById(e1.getId()).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(refreshed.getClaimToken()).isNull();
        assertThat(refreshed.getClaimedAt()).isNull();
        assertThat(refreshed.getRetryCount()).isEqualTo(initialRetry + 1);
    }

    @Test
    @Transactional
    void resetToPending_doesNotAffectPendingRows() {
        OutboxEvent e1 = outboxEventRepository.save(pendingEvent("topic-a", "key-1"));

        // Try to reset without claiming first (still PENDING)
        int reset = outboxEventRepository.resetToPending(List.of(e1.getId()));
        assertThat(reset).isZero();
    }

    // ---- resetStaleProcessing ----

    @Test
    @Transactional
    void resetStaleProcessing_resetsOnlyRowsOlderThanCutoff() {
        OutboxEvent stale = outboxEventRepository.save(pendingEvent("topic-a", "key-1"));
        OutboxEvent fresh = outboxEventRepository.save(pendingEvent("topic-a", "key-2"));

        // Claim both with different times
        outboxEventRepository.claimBatch(
                List.of(stale.getId()), "token-stale", BASE.minusMinutes(5));
        outboxEventRepository.claimBatch(
                List.of(fresh.getId()), "token-fresh", BASE);

        // Reap with cutoff = BASE - 2min (stale is 5min ago, fresh is at BASE)
        int reaped = outboxEventRepository.resetStaleProcessing(BASE.minusMinutes(2));
        assertThat(reaped).isEqualTo(1);

        // Stale -> PENDING, fresh still PROCESSING
        OutboxEvent staleRefreshed = outboxEventRepository.findById(stale.getId()).orElseThrow();
        assertThat(staleRefreshed.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(staleRefreshed.getClaimToken()).isNull();
        assertThat(staleRefreshed.getClaimedAt()).isNull();
        // Note: resetStaleProcessing does NOT retry++ (crash may not have been a real attempt)
        assertThat(staleRefreshed.getRetryCount()).isZero();

        OutboxEvent freshRefreshed = outboxEventRepository.findById(fresh.getId()).orElseThrow();
        assertThat(freshRefreshed.getStatus()).isEqualTo(OutboxStatus.PROCESSING);
        assertThat(freshRefreshed.getClaimToken()).isEqualTo("token-fresh");
    }

    // ---- markProcessed (from PROCESSING) ----

    @Test
    @Transactional
    void markProcessed_transitionsFromProcessingOnly() {
        OutboxEvent e1 = outboxEventRepository.save(pendingEvent("topic-a", "key-1"));

        // Try marking PENDING event as PROCESSED (should fail — WHERE status='PROCESSING')
        int updated = outboxEventRepository.markProcessed(List.of(e1.getId()), BASE);
        assertThat(updated).isZero();

        // Claim first, then mark
        outboxEventRepository.claimBatch(List.of(e1.getId()), "token-aaa", BASE);
        updated = outboxEventRepository.markProcessed(List.of(e1.getId()), BASE.plusSeconds(1));
        assertThat(updated).isEqualTo(1);

        OutboxEvent refreshed = outboxEventRepository.findById(e1.getId()).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(OutboxStatus.PROCESSED);
    }

    // ---- markFailed (from PROCESSING) ----

    @Test
    @Transactional
    void markFailed_transitionsFromProcessingOnly() {
        OutboxEvent e1 = outboxEventRepository.save(pendingEvent("topic-a", "key-1"));

        // Try marking PENDING event as FAILED (should fail — WHERE status='PROCESSING')
        int updated = outboxEventRepository.markFailed(List.of(e1.getId()), BASE);
        assertThat(updated).isZero();

        // Claim first, then mark
        outboxEventRepository.claimBatch(List.of(e1.getId()), "token-aaa", BASE);
        updated = outboxEventRepository.markFailed(List.of(e1.getId()), BASE.plusSeconds(1));
        assertThat(updated).isEqualTo(1);

        OutboxEvent refreshed = outboxEventRepository.findById(e1.getId()).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(OutboxStatus.FAILED);
    }

    // ---- countByStatus ----

    @Test
    @Transactional
    void countByStatus_returnsTrueCountNotCappedAt50() {
        // Insert 55 PENDING events — proves the count is not capped at 50
        for (int i = 0; i < 55; i++) {
            outboxEventRepository.save(pendingEvent("topic-a", "key-" + i));
        }
        // Insert 3 PROCESSED events — should not be counted
        for (int i = 0; i < 3; i++) {
            outboxEventRepository.save(
                    eventWithStatus("topic-a", "processed-" + i, OutboxStatus.PROCESSED));
        }

        long pendingCount = outboxEventRepository.countByStatus(OutboxStatus.PENDING);
        assertThat(pendingCount).isEqualTo(55);

        long processedCount = outboxEventRepository.countByStatus(OutboxStatus.PROCESSED);
        assertThat(processedCount).isEqualTo(3);

        long failedCount = outboxEventRepository.countByStatus(OutboxStatus.FAILED);
        assertThat(failedCount).isZero();
    }

    @Test
    @Transactional
    void countByStatus_returnsZeroWhenNoneExist() {
        long count = outboxEventRepository.countByStatus(OutboxStatus.PENDING);
        assertThat(count).isZero();
    }

    // ---- helpers ----

    private OutboxEvent pendingEvent(String topic, String partitionKey) {
        return OutboxEvent.builder()
                .topic(topic)
                .partitionKey(partitionKey)
                .payload("{\"msg\":\"test\"}")
                .status(OutboxStatus.PENDING)
                .aggregateType("ChatMessage")
                .aggregateId("msg-" + partitionKey)
                .eventType("MESSAGE_SENT")
                .retryCount(0)
                .createdAt(BASE)
                .build();
    }

    private OutboxEvent eventWithStatus(String topic, String partitionKey, OutboxStatus status) {
        return OutboxEvent.builder()
                .topic(topic)
                .partitionKey(partitionKey)
                .payload("{\"msg\":\"test\"}")
                .status(status)
                .aggregateType("ChatMessage")
                .aggregateId("msg-" + partitionKey)
                .eventType("MESSAGE_SENT")
                .retryCount(0)
                .createdAt(BASE)
                .processedAt(status == OutboxStatus.PROCESSED ? BASE : null)
                .build();
    }
}
