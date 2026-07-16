package com.chatflow.chat.repository;

import com.chatflow.chat.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findTop50ByStatusOrderByCreatedAtAsc(OutboxEvent.OutboxStatus status);

    /**
     * Atomically claim a batch of PENDING rows by flipping them to PROCESSING
     * with a unique claim token. Returns the count of rows actually claimed
     * (0 if another replica already claimed them).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE OutboxEvent e SET e.status = 'PROCESSING', e.claimToken = :token, e.claimedAt = :now " +
           "WHERE e.id IN :ids AND e.status = 'PENDING'")
    int claimBatch(@Param("ids") List<Long> ids,
                   @Param("token") String token,
                   @Param("now") LocalDateTime now);

    /**
     * Retrieve exactly the rows claimed by this poller's token.
     */
    List<OutboxEvent> findByClaimTokenAndStatus(String claimToken, OutboxEvent.OutboxStatus status);

    /**
     * Mark successfully-sent rows as PROCESSED. Only transitions from PROCESSING
     * (rows must be claimed before being processed).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE OutboxEvent e SET e.status = 'PROCESSED', e.processedAt = :now " +
           "WHERE e.id IN :ids AND e.status = 'PROCESSING'")
    int markProcessed(@Param("ids") List<Long> ids, @Param("now") LocalDateTime now);

    /**
     * Mark poison-pill rows as terminally FAILED. Only transitions from PROCESSING.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE OutboxEvent e SET e.status = 'FAILED', e.processedAt = :now " +
           "WHERE e.id IN :ids AND e.status = 'PROCESSING'")
    int markFailed(@Param("ids") List<Long> ids, @Param("now") LocalDateTime now);

    /**
     * Reset failed-but-retryable rows back to PENDING with retry++ and cleared claim.
     * Another poll cycle will re-pick them up.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE OutboxEvent e SET e.status = 'PENDING', e.claimToken = NULL, e.claimedAt = NULL, " +
           "e.retryCount = e.retryCount + 1 WHERE e.id IN :ids AND e.status = 'PROCESSING'")
    int resetToPending(@Param("ids") List<Long> ids);

    /**
     * Reaper: recover rows stuck in PROCESSING from a crashed poller.
     * Rows with claimedAt older than cutoff are reset to PENDING without retry++
     * (the crash may have occurred before Kafka send, so this is not a real attempt).
     *
     * Note: reset-to-PENDING may re-send an event that was actually delivered
     * right before a crash -- at-least-once; consumers (search-service upsert-by-id,
     * ai-summary) are already idempotent.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE OutboxEvent e SET e.status = 'PENDING', e.claimToken = NULL, e.claimedAt = NULL " +
           "WHERE e.status = 'PROCESSING' AND e.claimedAt < :cutoff")
    int resetStaleProcessing(@Param("cutoff") LocalDateTime cutoff);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM OutboxEvent e WHERE e.status = 'PROCESSED' AND e.processedAt < :before")
    int deleteProcessedBefore(@Param("before") LocalDateTime before);
}
