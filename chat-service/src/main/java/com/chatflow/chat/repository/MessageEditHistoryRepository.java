package com.chatflow.chat.repository;

import com.chatflow.chat.entity.MessageEditHistoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MessageEditHistoryRepository
        extends JpaRepository<MessageEditHistoryEntity, Long> {

    /**
     * Returns the message's edit history newest-first. Empty list when the
     * message has never been edited or doesn't exist.
     */
    List<MessageEditHistoryEntity> findByMessageIdOrderByEditedAtDesc(String messageId);
}
