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

    @Modifying
    @Transactional
    @Query(value = "DELETE FROM chat_messages WHERE message_id IN (SELECT message_id FROM chat_messages WHERE timestamp < :cutoff ORDER BY timestamp LIMIT :batchSize)", nativeQuery = true)
    int deleteBatchOlderThan(@Param("cutoff") LocalDateTime cutoff, @Param("batchSize") int batchSize);
    Page<ChatMessageEntity> findByChatRoomIdOrderByTimestampDesc(String chatRoomId, Pageable pageable);

    @Query("SELECT m FROM ChatMessageEntity m WHERE m.chatRoomId = :roomId AND m.timestamp < :cursor ORDER BY m.timestamp DESC")
    List<ChatMessageEntity> findByChatRoomIdBeforeCursor(
            @Param("roomId") String roomId,
            @Param("cursor") LocalDateTime cursor,
            Pageable pageable);

    @Query("SELECT m FROM ChatMessageEntity m WHERE m.chatRoomId = :roomId ORDER BY m.timestamp DESC")
    List<ChatMessageEntity> findLatestByChatRoomId(@Param("roomId") String roomId, Pageable pageable);

    List<ChatMessageEntity> findByParentMessageIdOrderByTimestampAsc(String parentMessageId);

    @Query("SELECT COUNT(m) FROM ChatMessageEntity m WHERE m.chatRoomId = :roomId AND m.timestamp > :after AND m.type = 'CHAT' AND m.deleted = false")
    long countNewChatMessages(@Param("roomId") String roomId, @Param("after") LocalDateTime after);

    /**
     * 여러 chatRoomId에 대한 메시지 카운트를 한 번에 조회.
     * readAt이 null인 roomIds (cutoff 공통)에 대한 배치 최적화용.
     */
    @Query("SELECT m.chatRoomId, COUNT(m) FROM ChatMessageEntity m " +
           "WHERE m.chatRoomId IN :roomIds " +
           "AND m.timestamp > :after " +
           "AND m.type = 'CHAT' AND m.deleted = false " +
           "GROUP BY m.chatRoomId")
    List<Object[]> countNewChatMessagesBatch(
            @Param("roomIds") List<String> roomIds,
            @Param("after") LocalDateTime after);

    @Modifying
    @Transactional
    @Query("DELETE FROM ChatMessageEntity m WHERE m.chatRoomId = :roomId")
    int deleteAllByChatRoomId(@Param("roomId") String roomId);
}
