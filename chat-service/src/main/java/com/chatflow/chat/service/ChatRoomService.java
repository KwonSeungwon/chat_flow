package com.chatflow.chat.service;

import com.chatflow.chat.entity.ChatMessageEntity;
import com.chatflow.chat.entity.ChatRoom;
import com.chatflow.chat.repository.ChatMessageRepository;
import com.chatflow.chat.repository.ChatRoomRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatRoomService {

    private static final int MAX_PARTICIPANTS = 10;
    private static final String ROOM_CACHE_KEY = "chatflow:room:";
    private static final String ROOMS_LIST_KEY = "chatflow:rooms:list";
    private static final Duration ROOM_TTL = Duration.ofMinutes(5);
    private static final Duration LIST_TTL = Duration.ofSeconds(30);

    private final ChatRoomRepository chatRoomRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public List<ChatRoom> getAllRooms() {
        try {
            String cached = redisTemplate.opsForValue().get(ROOMS_LIST_KEY);
            if (cached != null) {
                return objectMapper.readValue(cached, new TypeReference<List<ChatRoom>>() {});
            }
        } catch (Exception e) {
            log.debug("Room list cache miss or error: {}", e.getMessage());
        }

        List<ChatRoom> rooms = chatRoomRepository.findAllByOrderByCreatedAtDesc();

        try {
            redisTemplate.opsForValue().set(ROOMS_LIST_KEY, objectMapper.writeValueAsString(rooms), LIST_TTL);
        } catch (Exception e) {
            log.debug("Failed to cache room list: {}", e.getMessage());
        }
        return rooms;
    }

    public Optional<ChatRoom> getRoom(String id) {
        try {
            String cached = redisTemplate.opsForValue().get(ROOM_CACHE_KEY + id);
            if (cached != null) {
                return Optional.of(objectMapper.readValue(cached, ChatRoom.class));
            }
        } catch (Exception e) {
            log.debug("Room cache miss or error for {}: {}", id, e.getMessage());
        }

        Optional<ChatRoom> room = chatRoomRepository.findById(id);
        room.ifPresent(r -> cacheRoom(r));
        return room;
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
        evictRoomCaches(saved.getId());
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

    @Transactional
    public void incrementParticipantCount(String roomId) {
        chatRoomRepository.incrementParticipantCount(roomId);
        evictRoomCaches(roomId);
    }

    @Transactional
    public void decrementParticipantCount(String roomId) {
        chatRoomRepository.decrementParticipantCount(roomId);
        evictRoomCaches(roomId);
    }

    @Transactional(readOnly = true)
    public boolean isRoomFull(String roomId) {
        try {
            return chatRoomRepository.isRoomFull(roomId);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 기본 생성방이 꽉 차면 "일반-2", "일반-3" ... 자동 생성하여 반환.
     * 여유 있는 방이 있으면 기존 방 반환.
     */
    public ChatRoom findOrCreateAvailableRoom(String baseName) {
        List<ChatRoom> rooms = chatRoomRepository.findAllByOrderByCreatedAtDesc();

        // baseName으로 시작하는 방 중 여유 있는 방 찾기
        Optional<ChatRoom> available = rooms.stream()
                .filter(r -> r.getName().equals(baseName) || r.getName().matches(baseName + "-\\d+"))
                .filter(r -> r.getParticipantCount() < r.getMaxParticipants())
                .findFirst();

        if (available.isPresent()) {
            return available.get();
        }

        // 같은 시리즈 방 개수로 다음 번호 결정
        long count = rooms.stream()
                .filter(r -> r.getName().equals(baseName) || r.getName().matches(baseName + "-\\d+"))
                .count();

        String newName = baseName + "-" + (count + 1);
        ChatRoom newRoom = ChatRoom.builder()
                .id("room_" + UUID.randomUUID().toString().substring(0, 8))
                .name(newName)
                .description(baseName + " 채팅방 (자동 생성)")
                .color("#6366f1")
                .participantCount(0)
                .maxParticipants(MAX_PARTICIPANTS)
                .createdAt(LocalDateTime.now())
                .build();

        ChatRoom saved = chatRoomRepository.save(newRoom);
        evictRoomCaches(saved.getId());
        log.info("Auto-created overflow room: {} ({})", saved.getName(), saved.getId());
        return saved;
    }

    private void cacheRoom(ChatRoom room) {
        try {
            redisTemplate.opsForValue().set(
                    ROOM_CACHE_KEY + room.getId(),
                    objectMapper.writeValueAsString(room),
                    ROOM_TTL);
        } catch (Exception e) {
            log.debug("Failed to cache room {}: {}", room.getId(), e.getMessage());
        }
    }

    private void evictRoomCaches(String roomId) {
        try {
            redisTemplate.delete(ROOM_CACHE_KEY + roomId);
            redisTemplate.delete(ROOMS_LIST_KEY);
        } catch (Exception e) {
            log.debug("Failed to evict room cache: {}", e.getMessage());
        }
    }
}
