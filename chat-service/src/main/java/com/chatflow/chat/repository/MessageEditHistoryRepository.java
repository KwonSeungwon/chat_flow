package com.chatflow.chat.repository;

import com.chatflow.chat.entity.MessageEditHistoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface MessageEditHistoryRepository
        extends JpaRepository<MessageEditHistoryEntity, Long> {

    /**
     * Returns the message's edit history newest-first. Empty list when the
     * message has never been edited or doesn't exist.
     */
    List<MessageEditHistoryEntity> findByMessageIdOrderByEditedAtDesc(String messageId);

    /**
     * Deletes edit-history rows older than {@code cutoff}.
     * Native subquery form to stay compatible with PostgreSQL (DELETE
     * does not support LIMIT directly) — mirrors ChatMessageRepository.
     * Table columns: id, message_id, previous_content, edited_at, edited_by
     * (see V8 migration).
     */
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM message_edit_history WHERE id IN (SELECT id FROM message_edit_history WHERE edited_at < :cutoff ORDER BY edited_at LIMIT :batchSize)",
           nativeQuery = true)
    int deleteBatchOlderThan(@Param("cutoff") LocalDateTime cutoff,
                             @Param("batchSize") int batchSize);
}
