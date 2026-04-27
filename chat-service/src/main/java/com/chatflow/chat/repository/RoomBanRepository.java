package com.chatflow.chat.repository;

import com.chatflow.chat.entity.RoomBanEntity;
import com.chatflow.chat.entity.RoomBanId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RoomBanRepository extends JpaRepository<RoomBanEntity, RoomBanId> {

    boolean existsByRoomIdAndUserId(String roomId, String userId);

    List<RoomBanEntity> findByRoomId(String roomId);

    void deleteByRoomIdAndUserId(String roomId, String userId);
}
