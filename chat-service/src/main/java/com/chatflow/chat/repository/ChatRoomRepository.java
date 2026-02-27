package com.chatflow.chat.repository;

import com.chatflow.chat.entity.ChatRoom;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChatRoomRepository extends JpaRepository<ChatRoom, String> {
    List<ChatRoom> findAllByOrderByCreatedAtDesc();
    Optional<ChatRoom> findByExternalId(String externalId);
}
