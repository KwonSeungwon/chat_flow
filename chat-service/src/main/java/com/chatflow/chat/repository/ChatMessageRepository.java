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

    /**
     * Reply chain for the thread view — scoped to a specific chat room so a
     * messageId guessed from another room cannot leak replies, and
     * soft-deleted replies excluded at the database level.
     * Entity is returned directly so reactions/edited/pinned all flow to the
     * frontend.
     */
    List<ChatMessageEntity>
        findByChatRoomIdAndParentMessageIdAndDeletedFalseOrderByTimestampAsc(
            String chatRoomId, String parentMessageId);

    @Modifying
    @Transactional
    @Query("DELETE FROM ChatMessageEntity m WHERE m.chatRoomId = :roomId")
    int deleteAllByChatRoomId(@Param("roomId") String roomId);

    @Query("SELECT m.chatRoomId, COUNT(m) FROM ChatMessageEntity m, RoomMemberEntity rm " +
           "WHERE rm.userId = :userId AND rm.roomId = m.chatRoomId " +
           "AND m.chatRoomId IN :roomIds " +
           "AND m.timestamp > COALESCE(rm.lastReadAt, rm.joinedAt) " +
           "AND m.type = 'CHAT' AND m.deleted = false " +
           "GROUP BY m.chatRoomId")
    List<Object[]> countUnreadByCursor(@Param("userId") String userId,
                                       @Param("roomIds") List<String> roomIds);

}
