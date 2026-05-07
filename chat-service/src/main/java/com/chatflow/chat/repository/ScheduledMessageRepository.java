package com.chatflow.chat.repository;

import com.chatflow.chat.entity.ScheduledMessageEntity;
import com.chatflow.chat.entity.ScheduledMessageEntity.ScheduledMessageStatus;
import org.springframework.data.jpa.repository.JpaRepository;
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

    List<ScheduledMessageEntity> findByUserIdAndStatusOrderByScheduledAtDesc(
            String userId, ScheduledMessageStatus status);

    Optional<ScheduledMessageEntity> findByIdAndUserId(Long id, String userId);

    long countByUserIdAndStatus(String userId, ScheduledMessageStatus status);
}
