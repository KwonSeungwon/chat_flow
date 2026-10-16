package com.chatflow.chat.repository;

import com.chatflow.chat.entity.ScheduledMessageEntity;
import com.chatflow.chat.entity.ScheduledMessageEntity.ScheduledMessageStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ScheduledMessageRepository extends JpaRepository<ScheduledMessageEntity, Long> {

    @Query("SELECT s FROM ScheduledMessageEntity s " +
           "WHERE s.status = :status " +
           "  AND s.scheduledAt <= :now " +
           "ORDER BY s.scheduledAt ASC")
    List<ScheduledMessageEntity> findDueForSending(
            @Param("status") ScheduledMessageStatus status,
            @Param("now") LocalDateTime now);

    default List<ScheduledMessageEntity> findDueForSending(LocalDateTime now) {
        return findDueForSending(ScheduledMessageStatus.PENDING, now);
    }

    /**
     * Claims one due row for delivery: PENDING → PROCESSING, bumping the version so a
     * concurrent cancel loses its optimistic lock instead of overwriting a delivery.
     * Returns 0 when the row is no longer PENDING — another replica claimed it (the
     * row lock makes it wait for that replica's commit, then re-check) or it was canceled.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE ScheduledMessageEntity s SET s.status = 'PROCESSING', " +
           "s.version = s.version + 1, s.updatedAt = :now " +
           "WHERE s.id = :id AND s.status = 'PENDING'")
    int claim(@Param("id") Long id, @Param("now") LocalDateTime now);

    /**
     * Terminally fails a row whose delivery transaction rolled back. Never touches a
     * row that already reached SENT / CANCELED / FAILED.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE ScheduledMessageEntity s SET s.status = 'FAILED', s.errorMessage = :reason, " +
           "s.version = s.version + 1, s.updatedAt = :now " +
           "WHERE s.id = :id AND s.status IN ('PENDING', 'PROCESSING')")
    int markFailed(@Param("id") Long id, @Param("reason") String reason, @Param("now") LocalDateTime now);

    List<ScheduledMessageEntity> findByUserIdAndStatusOrderByScheduledAtDesc(
            String userId, ScheduledMessageStatus status);

    Optional<ScheduledMessageEntity> findByIdAndUserId(Long id, String userId);

    long countByUserIdAndStatus(String userId, ScheduledMessageStatus status);
}
