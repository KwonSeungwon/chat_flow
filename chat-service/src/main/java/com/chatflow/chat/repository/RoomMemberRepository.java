package com.chatflow.chat.repository;

import com.chatflow.chat.entity.RoomMemberEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface RoomMemberRepository extends JpaRepository<RoomMemberEntity, RoomMemberEntity.RoomMemberId> {
    boolean existsByRoomIdAndUserId(String roomId, String userId);
    long deleteByRoomIdAndUserId(String roomId, String userId);
    List<RoomMemberEntity> findByRoomId(String roomId);
    Optional<RoomMemberEntity> findByRoomIdAndUserId(String roomId, String userId);

    @Modifying
    @Transactional
    @Query("UPDATE RoomMemberEntity rm SET rm.lastReadAt = :at " +
           "WHERE rm.roomId = :roomId AND rm.userId = :userId")
    int touchLastReadAt(@Param("roomId") String roomId,
                        @Param("userId") String userId,
                        @Param("at") LocalDateTime at);
}
