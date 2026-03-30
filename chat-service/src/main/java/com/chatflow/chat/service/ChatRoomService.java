package com.chatflow.chat.service;

import com.chatflow.chat.entity.ChatMessageEntity;
import com.chatflow.chat.entity.ChatRoom;
import com.chatflow.chat.repository.ChatMessageRepository;
import com.chatflow.chat.repository.ChatRoomRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatRoomService {

    private static final int MAX_PARTICIPANTS = 10;

    private final ChatRoomRepository chatRoomRepository;
    private final ChatMessageRepository chatMessageRepository;

    public List<ChatRoom> getAllRooms() {
        return chatRoomRepository.findAllByOrderByCreatedAtDesc();
    }

    public Optional<ChatRoom> getRoom(String id) {
        return chatRoomRepository.findById(id);
    }

    public ChatRoom createRoom(ChatRoom request) {
        ChatRoom room = ChatRoom.builder()
                .id("room_" + UUID.randomUUID().toString().substring(0, 8))
                .name(request.getName().trim())
                .description(request.getDescription())
                .color(request.getColor() != null ? request.getColor() : "#6366f1")
                .isPrivate(request.isPrivate())
                .allowInvites(request.isAllowInvites())
                .participantCount(0)
                .maxParticipants(MAX_PARTICIPANTS)
                .createdAt(LocalDateTime.now())
                .build();

        ChatRoom saved = chatRoomRepository.save(room);
        log.info("Chat room created: {} ({})", saved.getName(), saved.getId());
        return saved;
    }

    public ChatRoom getOrCreateByExternalId(String externalId, String name, String description) {
        return chatRoomRepository.findByExternalId(externalId)
                .orElseGet(() -> {
                    ChatRoom newRoom = ChatRoom.builder()
                            .id("ext_" + UUID.randomUUID().toString().substring(0, 8))
                            .externalId(externalId)
                            .name(name != null ? name : externalId)
                            .description(description)
                            .color("#10b981")
                            .participantCount(0)
                            .maxParticipants(MAX_PARTICIPANTS)
                            .createdAt(LocalDateTime.now())
                            .build();
                    log.info("Auto-created room for external ID: {}", externalId);
                    return chatRoomRepository.save(newRoom);
                });
    }

    public Page<ChatMessageEntity> getMessages(String roomId, Pageable pageable) {
        return chatMessageRepository.findByChatRoomIdOrderByTimestampDesc(roomId, pageable);
    }

    public void incrementParticipantCount(String roomId) {
        chatRoomRepository.findById(roomId).ifPresent(room -> {
            room.setParticipantCount(room.getParticipantCount() + 1);
            chatRoomRepository.save(room);
        });
    }

    public void decrementParticipantCount(String roomId) {
        chatRoomRepository.findById(roomId).ifPresent(room -> {
            room.setParticipantCount(Math.max(0, room.getParticipantCount() - 1));
            chatRoomRepository.save(room);
        });
    }

    public boolean isRoomFull(String roomId) {
        return chatRoomRepository.findById(roomId)
                .map(room -> room.getParticipantCount() >= room.getMaxParticipants())
                .orElse(false);
    }
}
