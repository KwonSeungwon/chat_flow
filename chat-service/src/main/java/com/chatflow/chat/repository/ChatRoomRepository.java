package com.chatflow.chat.repository;

import com.chatflow.chat.entity.ChatRoom;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChatRoomRepository extends JpaRepository<ChatRoom, String> {
    @Query("SELECT r FROM ChatRoom r ORDER BY COALESCE(r.lastMessageAt, r.createdAt) DESC")
    List<ChatRoom> findAllOrderByLastActivity();

    /**
     * Room-list query mirroring RoomAccessPolicy#isVisible: non-DM non-private
     * rooms, plus any room the user holds a room_members row for.
     */
    @Query("SELECT r FROM ChatRoom r WHERE "
            + "((r.roomType IS NULL OR r.roomType <> com.chatflow.chat.entity.RoomType.DIRECT) AND r.isPrivate = false) "
            + "OR r.id IN (SELECT m.roomId FROM RoomMemberEntity m WHERE m.userId = :userId) "
            + "ORDER BY COALESCE(r.lastMessageAt, r.createdAt) DESC")
    List<ChatRoom> findVisibleOrderByLastActivity(@Param("userId") String userId);

    @Modifying
    @Query("UPDATE ChatRoom r SET r.participantCount = CASE WHEN r.participantCount > 0 THEN r.participantCount - 1 ELSE 0 END WHERE r.id = :roomId")
    int decrementParticipantCount(@Param("roomId") String roomId);

    @Query("SELECT CASE WHEN r.participantCount >= r.maxParticipants THEN true ELSE false END FROM ChatRoom r WHERE r.id = :roomId")
    boolean isRoomFull(@Param("roomId") String roomId);

    @Query("SELECT r FROM ChatRoom r WHERE r.roomType = com.chatflow.chat.entity.RoomType.DIRECT AND r.name IN (:name1, :name2)")
    List<ChatRoom> findDmRoom(@Param("name1") String name1, @Param("name2") String name2);

    @Query("SELECT r FROM ChatRoom r WHERE r.name = :baseName OR r.name LIKE CONCAT(:escapedPattern, '-%') ORDER BY r.createdAt ASC")
    List<ChatRoom> findByBaseName(@Param("baseName") String baseName, @Param("escapedPattern") String escapedPattern);

    /** DM room both users hold a room_members row in — identity by userId, not by name. */
    @Query("SELECT r FROM ChatRoom r WHERE r.roomType = com.chatflow.chat.entity.RoomType.DIRECT "
            + "AND r.id IN (SELECT m1.roomId FROM RoomMemberEntity m1 WHERE m1.userId = :userId1) "
            + "AND r.id IN (SELECT m2.roomId FROM RoomMemberEntity m2 WHERE m2.userId = :userId2) "
            + "ORDER BY r.createdAt ASC")
    List<ChatRoom> findDmRoomByMembers(@Param("userId1") String userId1, @Param("userId2") String userId2);

    @Query("SELECT r FROM ChatRoom r WHERE (r.name = :baseName OR r.name LIKE CONCAT(:escapedPattern, '-%')) AND r.participantCount < r.maxParticipants ORDER BY r.createdAt ASC")
    List<ChatRoom> findAvailableByBaseName(@Param("baseName") String baseName, @Param("escapedPattern") String escapedPattern);
}
