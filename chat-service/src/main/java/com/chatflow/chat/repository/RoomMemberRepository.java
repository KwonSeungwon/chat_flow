package com.chatflow.chat.repository;

import com.chatflow.chat.entity.RoomMemberEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RoomMemberRepository extends JpaRepository<RoomMemberEntity, RoomMemberEntity.RoomMemberId> {
    boolean existsByRoomIdAndUserId(String roomId, String userId);
    long deleteByRoomIdAndUserId(String roomId, String userId);
}
