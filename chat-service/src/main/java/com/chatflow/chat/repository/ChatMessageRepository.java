package com.chatflow.chat.repository;

import com.chatflow.chat.entity.ChatMessageEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, String> {
    Page<ChatMessageEntity> findByChatRoomIdOrderByTimestampDesc(String chatRoomId, Pageable pageable);
}
