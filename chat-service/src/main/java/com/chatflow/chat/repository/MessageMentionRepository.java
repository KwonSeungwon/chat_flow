package com.chatflow.chat.repository;

import com.chatflow.chat.entity.MessageMentionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface MessageMentionRepository extends JpaRepository<MessageMentionEntity, Long> {

    List<MessageMentionEntity> findByMentionedUserIdAndCreatedAtAfterOrderByCreatedAtDesc(
            String mentionedUserId, LocalDateTime since);

    long countByMentionedUserIdAndReadFalseAndCreatedAtAfter(
            String mentionedUserId, LocalDateTime since);

    @Modifying
    @Query("UPDATE MessageMentionEntity m SET m.read = true " +
           "WHERE m.mentionedUserId = :userId AND m.messageId = :messageId")
    int markRead(@Param("userId") String userId, @Param("messageId") String messageId);

    @Modifying
    @Query("UPDATE MessageMentionEntity m SET m.read = true " +
           "WHERE m.mentionedUserId = :userId AND m.read = false AND m.createdAt >= :since")
    int markAllRead(@Param("userId") String userId, @Param("since") LocalDateTime since);

    void deleteByMessageId(String messageId);

    /**
     * Retention purge: mention rows share their message's timestamp
     * (created_at == chat_messages.timestamp, both live-written and backfilled),
     * so deleting by the same cutoff removes exactly the mentions of purged
     * messages — otherwise unreadCount keeps counting orphans that list() drops.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM MessageMentionEntity m WHERE m.createdAt < :cutoff")
    int deleteByCreatedAtBefore(@Param("cutoff") LocalDateTime cutoff);
}
