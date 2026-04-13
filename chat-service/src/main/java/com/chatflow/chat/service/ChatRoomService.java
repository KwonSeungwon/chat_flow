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
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.LocalDateTime;
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
    private final RestClient restClient;

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
                .id("room_" + UUID.randomUUID().toString().substring(0, 8))
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
                    String stored = room.getPassword();
                    if (stored == null || stored.isEmpty()) return true;
                    // BCrypt 해시 감지 ($2a$, $2b$, $2y$)
                    if (stored.startsWith("$2")) {
                        return passwordEncoder.matches(password, stored);
                    }
                    // 레거시 평문 비밀번호 — 일치 시 자동 재해시 (마이그레이션)
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
        chatRoomRepository.findById(roomId).ifPresent(room -> {
            room.setParticipantCount(count);
            chatRoomRepository.save(room);
        });
        evictRoomCaches(roomId);
    }

    @Transactional(readOnly = true)
    public boolean isRoomFull(String roomId) {
        try {
            return chatRoomRepository.isRoomFull(roomId);
        } catch (Exception e) {
            log.warn("isRoomFull 조회 실패 — 안전을 위해 만석으로 처리: {}", roomId, e);
            return true;
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
        // 삭제 전 STOMP 브로드캐스트 — 연결된 클라이언트가 퇴장 처리
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
    public boolean deleteMessage(String messageId, String requestingUserId) {
        return chatMessageRepository.findById(messageId).map(entity -> {
            if (entity.getUserId() == null || !entity.getUserId().equals(requestingUserId)) {
                return false;
            }
            entity.setDeleted(true);
            entity.setContent("삭제된 메시지입니다.");
            chatMessageRepository.save(entity);
            Map<String, Object> broadcast = new LinkedHashMap<>();
            broadcast.put("type", "MESSAGE_DELETED");
            broadcast.put("messageId", messageId);
            broadcast.put("chatRoomId", entity.getChatRoomId());
            broadcast.put("content", "삭제된 메시지입니다.");
            broadcast.put("username", entity.getUsername());
            broadcast.put("timestamp", entity.getTimestamp().toString());
            messagingTemplate.convertAndSend("/topic/chat/" + entity.getChatRoomId(), broadcast);
            log.info("Message deleted: {} by user {}", messageId, requestingUserId);
            return true;
        }).orElse(false);
    }

    @Transactional
    public boolean editMessage(String messageId, String requestingUserId, String newContent) {
        return chatMessageRepository.findById(messageId).map(entity -> {
            if (entity.getUserId() == null || !entity.getUserId().equals(requestingUserId)) {
                return false;
            }
            if (entity.isDeleted()) return false;
            entity.setContent(messageEncryptor.isEnabled() ? messageEncryptor.encrypt(newContent) : newContent);
            entity.setEdited(true);
            entity.setEditedAt(LocalDateTime.now());
            chatMessageRepository.save(entity);

            Map<String, Object> broadcast = new LinkedHashMap<>();
            broadcast.put("type", "MESSAGE_EDITED");
            broadcast.put("messageId", messageId);
            broadcast.put("chatRoomId", entity.getChatRoomId());
            broadcast.put("content", newContent);
            broadcast.put("username", entity.getUsername());
            broadcast.put("timestamp", entity.getTimestamp().toString());
            broadcast.put("editedAt", entity.getEditedAt().toString());
            messagingTemplate.convertAndSend("/topic/chat/" + entity.getChatRoomId(), broadcast);
            log.info("Message edited: {} by user {}", messageId, requestingUserId);
            return true;
        }).orElse(false);
    }

    public void leaveRoom(String roomId, String userId, String username) {
        // Redis SET에서 해당 유저의 모든 세션 제거 (userId prefix로 매칭 — 스푸핑 방지)
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
        Map<String, Long> result = new LinkedHashMap<>();
        for (String roomId : roomIds) {
            try {
                String atKey = "chatflow:readat:" + roomId + ":" + userId;
                String readAtStr = redisTemplate.opsForValue().get(atKey);
                if (readAtStr == null) {
                    // 읽은 기록 없음 — 전체 메시지 카운트
                    result.put(roomId, chatMessageRepository.countNewChatMessages(roomId, LocalDateTime.of(2000, 1, 1, 0, 0)));
                } else {
                    LocalDateTime readAt = LocalDateTime.parse(readAtStr);
                    result.put(roomId, chatMessageRepository.countNewChatMessages(roomId, readAt));
                }
            } catch (Exception e) {
                result.put(roomId, 0L);
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
        ChatRoom saved = chatRoomRepository.save(dm);
        evictRoomCaches(saved.getId());
        return saved;
    }

    public Map<String, String> fetchLinkPreview(String url) {
        Map<String, String> result = new LinkedHashMap<>();
        try {
            String html = restClient.get()
                    .uri(url)
                    .header("User-Agent", "Mozilla/5.0 ChatFlow-Bot")
                    .retrieve()
                    .body(String.class);
            if (html != null) {
                result.put("url", url);
                extractOg(html, "og:title", result, "title");
                extractOg(html, "og:description", result, "description");
                extractOg(html, "og:image", result, "image");
                if (!result.containsKey("title")) {
                    var m = java.util.regex.Pattern.compile("<title[^>]*>([^<]+)</title>", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html);
                    if (m.find()) result.put("title", m.group(1).trim());
                }
            }
        } catch (Exception e) {
            log.debug("Link preview fetch failed: {}", e.getMessage());
        }
        return result;
    }

    private void extractOg(String html, String property, Map<String, String> result, String key) {
        var pattern = java.util.regex.Pattern.compile(
                "meta[^>]+property=[\"']" + property + "[\"'][^>]+content=[\"']([^\"']+)[\"']",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        var m = pattern.matcher(html);
        if (m.find()) result.put(key, m.group(1));
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
    public boolean toggleReaction(String messageId, String emoji, String userId) {
        return chatMessageRepository.findById(messageId).map(entity -> {
            Map<String, List<String>> map;
            try {
                map = entity.getReactions() != null
                        ? objectMapper.readValue(entity.getReactions(), new TypeReference<>() {})
                        : new LinkedHashMap<>();
            } catch (Exception e) {
                map = new LinkedHashMap<>();
            }
            List<String> users = map.computeIfAbsent(emoji, k -> new java.util.ArrayList<>());
            if (users.contains(userId)) {
                users.remove(userId);
                if (users.isEmpty()) map.remove(emoji);
            } else {
                users.add(userId);
            }
            try {
                entity.setReactions(map.isEmpty() ? null : objectMapper.writeValueAsString(map));
            } catch (Exception e) {
                return false;
            }
            chatMessageRepository.save(entity);
            // Broadcast reaction update
            Map<String, Object> broadcast = new LinkedHashMap<>();
            broadcast.put("type", "REACTION_UPDATED");
            broadcast.put("messageId", messageId);
            broadcast.put("reactions", map);
            messagingTemplate.convertAndSend("/topic/chat/" + entity.getChatRoomId(), broadcast);
            return true;
        }).orElse(false);
    }

    @Transactional
    public boolean pinMessage(String roomId, String messageId) {
        return chatRoomRepository.findById(roomId).map(room -> {
            room.setPinnedMessageId(messageId);
            chatRoomRepository.save(room);
            chatMessageRepository.findById(messageId).ifPresent(msg -> {
                msg.setPinned(true);
                chatMessageRepository.save(msg);
            });
            evictRoomCaches(roomId);
            Map<String, Object> broadcast = new LinkedHashMap<>();
            broadcast.put("type", "MESSAGE_PINNED");
            broadcast.put("messageId", messageId);
            broadcast.put("chatRoomId", roomId);
            messagingTemplate.convertAndSend("/topic/chat/" + roomId, broadcast);
            return true;
        }).orElse(false);
    }

    @Transactional
    public boolean unpinMessage(String roomId) {
        return chatRoomRepository.findById(roomId).map(room -> {
            String oldPin = room.getPinnedMessageId();
            room.setPinnedMessageId(null);
            chatRoomRepository.save(room);
            if (oldPin != null) {
                chatMessageRepository.findById(oldPin).ifPresent(msg -> {
                    msg.setPinned(false);
                    chatMessageRepository.save(msg);
                });
            }
            evictRoomCaches(roomId);
            Map<String, Object> broadcast = new LinkedHashMap<>();
            broadcast.put("type", "MESSAGE_UNPINNED");
            broadcast.put("chatRoomId", roomId);
            messagingTemplate.convertAndSend("/topic/chat/" + roomId, broadcast);
            return true;
        }).orElse(false);
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
