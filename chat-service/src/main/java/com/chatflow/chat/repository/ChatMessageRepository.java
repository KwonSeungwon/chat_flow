package com.chatflow.chat.repository;

import com.chatflow.chat.entity.ChatMessageEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, String> {

    @Modifying
    @Transactional
    @Query("DELETE FROM ChatMessageEntity m WHERE m.timestamp < :cutoff")
    int deleteMessagesOlderThan(@Param("cutoff") LocalDateTime cutoff);
    Page<ChatMessageEntity> findByChatRoomIdOrderByTimestampDesc(String chatRoomId, Pageable pageable);

    @Query("SELECT m FROM ChatMessageEntity m WHERE m.chatRoomId = :roomId AND m.timestamp < :cursor ORDER BY m.timestamp DESC")
    List<ChatMessageEntity> findByChatRoomIdBeforeCursor(
            @Param("roomId") String roomId,
            @Param("cursor") LocalDateTime cursor,
            Pageable pageable);

    @Query("SELECT m FROM ChatMessageEntity m WHERE m.chatRoomId = :roomId ORDER BY m.timestamp DESC")
    List<ChatMessageEntity> findLatestByChatRoomId(@Param("roomId") String roomId, Pageable pageable);

    List<ChatMessageEntity> findByParentMessageIdOrderByTimestampAsc(String parentMessageId);

    @Modifying
    @Transactional
    @Query("DELETE FROM ChatMessageEntity m WHERE m.chatRoomId = :roomId")
    int deleteAllByChatRoomId(@Param("roomId") String roomId);
}
