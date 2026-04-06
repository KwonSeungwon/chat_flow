package com.chatflow.chat.service;

import com.chatflow.chat.config.RedisHealthTracker;
import com.chatflow.chat.entity.ChatMessageEntity;
import com.chatflow.chat.entity.ChatRoom;
import com.chatflow.chat.repository.ChatMessageRepository;
import com.chatflow.chat.repository.ChatRoomRepository;
import com.chatflow.common.util.MessageEncryptor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

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
    private final RedisHealthTracker redisHealth;
    private final MessageEncryptor messageEncryptor;

    public List<ChatRoom> getAllRooms() {
        if (!redisHealth.isCircuitOpen()) {
            try {
                String cached = redisTemplate.opsForValue().get(ROOMS_LIST_KEY);
                if (cached != null) {
                    redisHealth.recordSuccess();
                    return objectMapper.readValue(cached, new TypeReference<List<ChatRoom>>() {});
                }
            } catch (Exception e) {
                redisHealth.recordFailure(e);
            }
        }

        List<ChatRoom> rooms = chatRoomRepository.findAllByOrderByCreatedAtDesc();
        cacheValue(ROOMS_LIST_KEY, rooms, LIST_TTL);
        return rooms;
    }

    public Optional<ChatRoom> getRoom(String id) {
        if (!redisHealth.isCircuitOpen()) {
            try {
                String cached = redisTemplate.opsForValue().get(ROOM_CACHE_KEY + id);
                if (cached != null) {
                    redisHealth.recordSuccess();
                    return Optional.of(objectMapper.readValue(cached, ChatRoom.class));
                }
            } catch (Exception e) {
                redisHealth.recordFailure(e);
            }
        }

        Optional<ChatRoom> room = chatRoomRepository.findById(id);
        room.ifPresent(r -> cacheValue(ROOM_CACHE_KEY + r.getId(), r, ROOM_TTL));
        return room;
    }

    public ChatRoom createRoom(ChatRoom request) {
        ChatRoom room = ChatRoom.builder()
                .id("room_" + UUID.randomUUID().toString().substring(0, 8))
                .name(request.getName().trim())
                .description(request.getDescription())
                .color(request.getColor() != null ? request.getColor() : "#6366f1")
                .roomType(request.getRoomType() != null ? request.getRoomType() : com.chatflow.chat.entity.RoomType.GENERAL)
                .isPrivate(request.isPrivate())
                .password(request.getPassword())
                .allowedRoles(request.getAllowedRoles())
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
        Page<ChatMessageEntity> page = chatMessageRepository.findByChatRoomIdOrderByTimestampDesc(roomId, pageable);
        if (!messageEncryptor.isEnabled()) return page;
        List<ChatMessageEntity> decrypted = page.getContent().stream()
                .map(this::decryptEntity)
                .collect(Collectors.toList());
        return new PageImpl<>(decrypted, pageable, page.getTotalElements());
    }

    public List<ChatMessageEntity> getMessagesByCursor(String roomId, LocalDateTime before, int size) {
        Pageable limit = Pageable.ofSize(size);
        List<ChatMessageEntity> messages = before == null
                ? chatMessageRepository.findLatestByChatRoomId(roomId, limit)
                : chatMessageRepository.findByChatRoomIdBeforeCursor(roomId, before, limit);
        if (!messageEncryptor.isEnabled()) return messages;
        return messages.stream().map(this::decryptEntity).collect(Collectors.toList());
    }

    private ChatMessageEntity decryptEntity(ChatMessageEntity entity) {
        if (entity.getContent() == null) return entity;
        entity.setContent(messageEncryptor.decrypt(entity.getContent()));
        return entity;
    }

    public boolean verifyRoomPassword(String roomId, String password) {
        return chatRoomRepository.findById(roomId)
                .map(room -> {
                    if (room.getPassword() == null || room.getPassword().isEmpty()) return true;
                    return room.getPassword().equals(password);
                })
                .orElse(false);
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

    public ChatRoom findOrCreateAvailableRoom(String baseName) {
        List<ChatRoom> rooms = chatRoomRepository.findAllByOrderByCreatedAtDesc();

        Optional<ChatRoom> available = rooms.stream()
                .filter(r -> r.getName().equals(baseName) || r.getName().matches(baseName + "-\\d+"))
                .filter(r -> !r.isFull())
                .findFirst();

        if (available.isPresent()) {
            return available.get();
        }

        long count = rooms.stream()
                .filter(r -> r.getName().equals(baseName) || r.getName().matches(baseName + "-\\d+"))
                .count();

        String newName = ChatRoom.nextOverflowName(baseName, count);
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

    private <T> void cacheValue(String key, T value, Duration ttl) {
        if (redisHealth.isCircuitOpen()) return;
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
            redisHealth.recordSuccess();
        } catch (Exception e) {
            redisHealth.recordFailure(e);
        }
    }

    private void evictRoomCaches(String roomId) {
        if (redisHealth.isCircuitOpen()) return;
        try {
            redisTemplate.delete(ROOM_CACHE_KEY + roomId);
            redisTemplate.delete(ROOMS_LIST_KEY);
            redisHealth.recordSuccess();
        } catch (Exception e) {
            redisHealth.recordFailure(e);
        }
    }
}
