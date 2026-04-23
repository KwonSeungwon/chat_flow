package com.chatflow.chat.service;

import com.chatflow.chat.config.RedisHealthTracker;
import com.chatflow.chat.entity.ChatMessageEntity;
import com.chatflow.chat.entity.ChatRoom;
import com.chatflow.chat.entity.RoomType;
import com.chatflow.chat.repository.ChatMessageRepository;
import com.chatflow.chat.repository.ChatRoomRepository;
import com.chatflow.common.util.MessageEncryptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    private final PasswordEncoder passwordEncoder;
    private final SimpMessagingTemplate messagingTemplate;

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

    public ChatRoom createRoom(ChatRoom request, String creatorId) {
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
        evictRoomCaches(saved.getId());
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
                        evictRoomCaches(roomId);
                        return true;
                    }
                    return false;
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

    @Transactional
    public void setParticipantCount(String roomId, int count) {
        chatRoomRepository.findById(roomId).ifPresent(room ->
            room.setParticipantCount(count)
        );
        evictRoomCaches(roomId);
    }

    @Transactional(readOnly = true)
    public boolean isRoomFull(String roomId) {
        try {
            return chatRoomRepository.isRoomFull(roomId);
        } catch (Exception e) {
            log.warn("isRoomFull 조회 실패 -- 안전을 위해 만석으로 처리: {}", roomId, e);
            return true;
        }
    }

    @Transactional
    public ChatRoom findOrCreateAvailableRoom(String baseName) {
        String escapedPattern = baseName.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        List<ChatRoom> available = chatRoomRepository.findAvailableByBaseName(baseName, escapedPattern);

        if (!available.isEmpty()) {
            return available.get(0);
        }

        long count = (long) chatRoomRepository.findByBaseName(baseName, escapedPattern).size();

        String newName = ChatRoom.nextOverflowName(baseName, count);
        ChatRoom newRoom = ChatRoom.builder()
                .id("room_" + UUID.randomUUID().toString())
                .name(newName)
                .description(baseName + " 채팅방 (자동 생성)")
                .color("#6366f1")
                .participantCount(0)
                .maxParticipants(MAX_PARTICIPANTS)
                .createdAt(LocalDateTime.now())
                .build();

        try {
            ChatRoom saved = chatRoomRepository.save(newRoom);
            evictRoomCaches(saved.getId());
            log.info("Auto-created overflow room: {} ({})", saved.getName(), saved.getId());
            return saved;
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            log.warn("Concurrent room creation detected for '{}', returning existing room", newName);
            List<ChatRoom> retry = chatRoomRepository.findByBaseName(newName,
                    newName.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_"));
            if (!retry.isEmpty()) return retry.get(0);
            throw e;
        }
    }

    public void sendInviteMessage(String roomId, String inviterName, String targetUsername) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "SYSTEM");
        msg.put("chatRoomId", roomId);
        msg.put("username", "SYSTEM");
        msg.put("messageId", UUID.randomUUID().toString());
        msg.put("timestamp", LocalDateTime.now().toString());
        msg.put("content", (inviterName != null ? inviterName : "누군가") +
                "님이 " + targetUsername + "님을 채팅방에 초대했습니다.");
        messagingTemplate.convertAndSend("/topic/chat/" + roomId, msg);
        log.info("Invite message sent: {} invited {} to room {}", inviterName, targetUsername, roomId);
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
        evictRoomCaches(id);
        log.info("Chat room deleted: {}", id);
    }

    @Transactional
    public void leaveRoom(String roomId, String userId, String username) {
        // Redis SET에서 해당 유저의 모든 세션 제거 (userId prefix로 매칭 -- 스푸핑 방지)
        String participantKey = "chatflow:room:participants:" + roomId;
        if (!redisHealth.isCircuitOpen()) {
            try {
                Set<String> members = redisTemplate.opsForSet().members(participantKey);
                if (members != null) {
                    members.stream()
                        .filter(e -> e.startsWith(userId + ":"))
                        .forEach(e -> redisTemplate.opsForSet().remove(participantKey, e));
                }
                redisHealth.recordSuccess();
            } catch (Exception e) {
                redisHealth.recordFailure(e);
            }
        }
        // 퇴장 시스템 메시지 브로드캐스트
        Map<String, Object> leaveMsg = new LinkedHashMap<>();
        leaveMsg.put("type", "LEAVE");
        leaveMsg.put("chatRoomId", roomId);
        leaveMsg.put("username", username);
        leaveMsg.put("messageId", UUID.randomUUID().toString());
        leaveMsg.put("timestamp", LocalDateTime.now().toString());
        leaveMsg.put("content", username + "님이 채팅방을 나갔습니다.");
        messagingTemplate.convertAndSend("/topic/chat/" + roomId, leaveMsg);
        // 참가자 수 동기화
        decrementParticipantCount(roomId);
        evictRoomCaches(roomId);
        log.info("User {} left room {} via REST API", username, roomId);
    }

    public Map<String, Long> getUnreadCounts(String userId, List<String> roomIds) {
        if (roomIds.isEmpty()) return Collections.emptyMap();

        List<String> keys = roomIds.stream()
                .map(id -> "chatflow:readat:" + id + ":" + userId)
                .collect(Collectors.toList());

        List<String> values;
        try {
            if (redisHealth.isCircuitOpen()) {
                values = null;
            } else {
                values = redisTemplate.opsForValue().multiGet(keys);
                redisHealth.recordSuccess();
            }
        } catch (Exception e) {
            redisHealth.recordFailure(e);
            values = null;
        }

        // readAt 기준으로 2그룹 분리
        List<String> unreadAllRoomIds = new ArrayList<>();          // readAt null: 전체 카운트
        Map<String, LocalDateTime> readAtByRoom = new LinkedHashMap<>();  // readAt 존재
        for (int i = 0; i < roomIds.size(); i++) {
            String roomId = roomIds.get(i);
            String readAtStr = (values != null) ? values.get(i) : null;
            if (readAtStr == null) {
                unreadAllRoomIds.add(roomId);
            } else {
                try {
                    readAtByRoom.put(roomId, LocalDateTime.parse(readAtStr));
                } catch (Exception e) {
                    unreadAllRoomIds.add(roomId);  // 파싱 실패 시 전체 카운트로 대체
                }
            }
        }

        Map<String, Long> result = new LinkedHashMap<>();
        // roomIds 순서 보존을 위해 모든 roomId를 0L로 초기화
        for (String roomId : roomIds) result.put(roomId, 0L);

        // Group A: readAt null -- 단일 배치 쿼리
        if (!unreadAllRoomIds.isEmpty()) {
            LocalDateTime epoch = LocalDateTime.of(2000, 1, 1, 0, 0);
            try {
                List<Object[]> rows = chatMessageRepository.countNewChatMessagesBatch(unreadAllRoomIds, epoch);
                for (Object[] row : rows) {
                    String roomId = (String) row[0];
                    Long count = ((Number) row[1]).longValue();
                    result.put(roomId, count);
                }
            } catch (Exception e) {
                // 배치 쿼리 실패 시 개별 쿼리 폴백
                for (String roomId : unreadAllRoomIds) {
                    try {
                        result.put(roomId, chatMessageRepository.countNewChatMessages(roomId, epoch));
                    } catch (Exception ex) {
                        result.put(roomId, 0L);
                    }
                }
            }
        }

        // Group B: readAt 존재 -- 각 roomId별 개별 쿼리 (cutoff 다름)
        for (Map.Entry<String, LocalDateTime> entry : readAtByRoom.entrySet()) {
            try {
                result.put(entry.getKey(),
                        chatMessageRepository.countNewChatMessages(entry.getKey(), entry.getValue()));
            } catch (Exception e) {
                result.put(entry.getKey(), 0L);
            }
        }

        return result;
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
    public ChatRoom createOrFindDmRoom(String userId1, String username1, String userId2, String username2) {
        // DM room name is canonical: sorted usernames
        String name1 = "DM:" + username1 + "," + username2;
        String name2 = "DM:" + username2 + "," + username1;
        List<ChatRoom> existing = chatRoomRepository.findDmRoom(name1, name2);
        if (!existing.isEmpty()) return existing.get(0);

        ChatRoom dm = ChatRoom.builder()
                .id(UUID.randomUUID().toString())
                .name(name1)
                .description(username1 + "님과 " + username2 + "님의 대화")
                .roomType(RoomType.DIRECT)
                .maxParticipants(2)
                .participantCount(0)
                .createdAt(LocalDateTime.now())
                .build();
        try {
            ChatRoom saved = chatRoomRepository.save(dm);
            evictRoomCaches(saved.getId());
            return saved;
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // TOCTOU: 동시 요청으로 중복 생성 시 기존 방 반환
            log.warn("DM room race condition detected, re-querying: {} <-> {}", username1, username2);
            List<ChatRoom> retry = chatRoomRepository.findDmRoom(name1, name2);
            if (!retry.isEmpty()) return retry.get(0);
            throw e;
        }
    }

    @Transactional
    public void updateLastMessageAt(String roomId) {
        chatRoomRepository.findById(roomId).ifPresent(room -> {
            room.setLastMessageAt(LocalDateTime.now());
            chatRoomRepository.save(room);
            evictRoomCaches(roomId);
        });
    }

    @Transactional
    public boolean updateRoomSettings(String roomId, String name, String description) {
        return chatRoomRepository.findById(roomId).map(room -> {
            if (name != null && !name.isBlank()) room.setName(name);
            if (description != null) room.setDescription(description);
            chatRoomRepository.save(room);
            evictRoomCaches(roomId);
            return true;
        }).orElse(false);
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
