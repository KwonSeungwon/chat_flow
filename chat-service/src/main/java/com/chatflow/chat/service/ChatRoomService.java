package com.chatflow.chat.service;

import com.chatflow.chat.config.RedisHealthTracker;
import com.chatflow.chat.entity.ChatRoom;
import com.chatflow.chat.entity.RoomRole;
import com.chatflow.chat.repository.ChatMessageRepository;
import com.chatflow.chat.repository.ChatRoomRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Room CRUD + cache. Membership-related lifecycle (addMemberIfAbsent,
 * leaveRoom, sendInviteMessage) lives in RoomMembershipService.
 */
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
    private final PasswordEncoder passwordEncoder;
    private final SimpMessagingTemplate messagingTemplate;
    private final RoomCacheEvictor roomCacheEvictor;
    private final RoomMembershipService roomMembershipService;

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

        List<ChatRoom> rooms = chatRoomRepository.findAllOrderByLastActivity();
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

    public ChatRoom createRoom(ChatRoom request, String creatorId, String creatorUsername) {
        ChatRoom room = ChatRoom.builder()
                .id("room_" + UUID.randomUUID().toString())
                .name(request.getName().trim())
                .description(request.getDescription())
                .color(request.getColor() != null ? request.getColor() : "#6366f1")
                .roomType(request.getRoomType() != null ? request.getRoomType() : com.chatflow.chat.entity.RoomType.GENERAL)
                .isPrivate(request.isPrivate())
                .password(request.getPassword() != null && !request.getPassword().isBlank()
                        ? passwordEncoder.encode(request.getPassword()) : null)
                .allowedRoles(request.getAllowedRoles())
                .allowInvites(request.isAllowInvites())
                .participantCount(0)
                .maxParticipants(MAX_PARTICIPANTS)
                .createdBy(creatorId)
                .createdAt(LocalDateTime.now())
                .build();

        ChatRoom saved = chatRoomRepository.save(room);
        // Seed room_members with the creator so subsequent membership checks
        // (replies endpoint, etc.) recognize them without waiting for a STOMP
        // join. Without this, the creator can chat in their own room but cannot
        // call any endpoint that requires existsByRoomIdAndUserId.
        // Creator gets OWNER so they can use moderation features
        // (mute/ban/role-transfer) without an extra promotion step.
        roomMembershipService.addMemberIfAbsent(saved.getId(), creatorId,
                creatorUsername != null && !creatorUsername.isBlank() ? creatorUsername : creatorId,
                RoomRole.OWNER);
        roomCacheEvictor.evict(saved.getId());
        log.info("Chat room created: {} ({})", saved.getName(), saved.getId());
        return saved;
    }

    public ChatRoom getOrCreateByExternalId(String externalId, String name, String description) {
        return chatRoomRepository.findByExternalId(externalId)
                .orElseGet(() -> {
                    ChatRoom newRoom = ChatRoom.builder()
                            .id("ext_" + UUID.randomUUID().toString())
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

    public boolean verifyRoomPassword(String roomId, String password) {
        return chatRoomRepository.findById(roomId)
                .map(room -> {
                    String stored = room.getPassword();
                    if (stored == null || stored.isEmpty()) return true;
                    // BCrypt 해시 감지 ($2a$, $2b$, $2y$)
                    if (stored.startsWith("$2")) {
                        return passwordEncoder.matches(password, stored);
                    }
                    // 레거시 평문 비밀번호 -- 일치 시 자동 재해시 (마이그레이션)
                    if (java.security.MessageDigest.isEqual(
                            stored.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            password.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                        room.setPassword(passwordEncoder.encode(password));
                        chatRoomRepository.save(room);
                        roomCacheEvictor.evict(roomId);
                        return true;
                    }
                    return false;
                })
                .orElse(false);
    }

    @Transactional
    public void deleteRoom(String id) {
        // 삭제 전 STOMP 브로드캐스트 -- 연결된 클라이언트가 퇴장 처리
        Map<String, Object> deletedEvent = new LinkedHashMap<>();
        deletedEvent.put("type", "ROOM_DELETED");
        deletedEvent.put("chatRoomId", id);
        messagingTemplate.convertAndSend("/topic/chat/" + id, deletedEvent);

        chatMessageRepository.deleteAllByChatRoomId(id);
        chatRoomRepository.deleteById(id);
        if (!redisHealth.isCircuitOpen()) {
            try {
                redisTemplate.delete("chatflow:room:participants:" + id);
                redisHealth.recordSuccess();
            } catch (Exception e) {
                redisHealth.recordFailure(e);
            }
        }
        roomCacheEvictor.evict(id);
        log.info("Chat room deleted: {}", id);
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

    @Transactional
    public void updateLastMessageAt(String roomId) {
        chatRoomRepository.findById(roomId).ifPresent(room -> {
            room.setLastMessageAt(LocalDateTime.now());
            chatRoomRepository.save(room);
            roomCacheEvictor.evict(roomId);
        });
    }

    @Transactional
    public boolean updateRoomSettings(String roomId, String name, String description) {
        return chatRoomRepository.findById(roomId).map(room -> {
            if (name != null && !name.isBlank()) room.setName(name);
            if (description != null) room.setDescription(description);
            chatRoomRepository.save(room);
            roomCacheEvictor.evict(roomId);
            return true;
        }).orElse(false);
    }

}
