package com.chatflow.chat.repository;

import com.chatflow.chat.entity.RoomMemberEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RoomMemberRepository extends JpaRepository<RoomMemberEntity, RoomMemberEntity.RoomMemberId> {
    boolean existsByRoomIdAndUserId(String roomId, String userId);
    long deleteByRoomIdAndUserId(String roomId, String userId);
    List<RoomMemberEntity> findByRoomId(String roomId);
    Optional<RoomMemberEntity> findByRoomIdAndUserId(String roomId, String userId);
}
