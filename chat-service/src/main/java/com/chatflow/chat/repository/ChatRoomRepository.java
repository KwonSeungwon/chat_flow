package com.chatflow.chat.repository;

import com.chatflow.chat.entity.ChatRoom;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChatRoomRepository extends JpaRepository<ChatRoom, String> {
    List<ChatRoom> findAllByOrderByCreatedAtDesc();

    @Query("SELECT r FROM ChatRoom r ORDER BY COALESCE(r.lastMessageAt, r.createdAt) DESC")
    List<ChatRoom> findAllOrderByLastActivity();
    Optional<ChatRoom> findByExternalId(String externalId);

    @Modifying
    @Query("UPDATE ChatRoom r SET r.participantCount = r.participantCount + 1 WHERE r.id = :roomId")
    int incrementParticipantCount(@Param("roomId") String roomId);

    @Modifying
    @Query("UPDATE ChatRoom r SET r.participantCount = CASE WHEN r.participantCount > 0 THEN r.participantCount - 1 ELSE 0 END WHERE r.id = :roomId")
    int decrementParticipantCount(@Param("roomId") String roomId);

    @Query("SELECT CASE WHEN r.participantCount >= r.maxParticipants THEN true ELSE false END FROM ChatRoom r WHERE r.id = :roomId")
    boolean isRoomFull(@Param("roomId") String roomId);

    @Query("SELECT r FROM ChatRoom r WHERE r.roomType = 'DIRECT' AND r.name IN (:name1, :name2)")
    List<ChatRoom> findDmRoom(@Param("name1") String name1, @Param("name2") String name2);

    @Query("SELECT r FROM ChatRoom r WHERE r.name = :baseName OR r.name LIKE CONCAT(:escapedPattern, '-%') ORDER BY r.createdAt ASC")
    List<ChatRoom> findByBaseName(@Param("baseName") String baseName, @Param("escapedPattern") String escapedPattern);

    @Query("SELECT r FROM ChatRoom r WHERE (r.name = :baseName OR r.name LIKE CONCAT(:escapedPattern, '-%')) AND r.participantCount < r.maxParticipants ORDER BY r.createdAt ASC")
    List<ChatRoom> findAvailableByBaseName(@Param("baseName") String baseName, @Param("escapedPattern") String escapedPattern);
}
