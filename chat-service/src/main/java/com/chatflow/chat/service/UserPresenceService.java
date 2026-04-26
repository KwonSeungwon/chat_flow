package com.chatflow.chat.service;

import com.chatflow.chat.entity.ChatRoom;
import com.chatflow.chat.entity.RoomType;
import com.chatflow.common.dto.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserPresenceService {

    private final SimpMessagingTemplate messagingTemplate;
    private final ChatPersistenceService chatPersistenceService;
    private final ChatRoomService chatRoomService;
    private final ParticipantService participantService;
    private final StringRedisTemplate redisTemplate;

    private static final String CHAT_TOPIC = "chat-messages";

    public void join(ChatMessage message, String sessionId) {
        // 본인이 이미 다른 세션으로 참여 중인지 — 만석 분기 skip + system JOIN 노이즈 억제용
        Set<String> existingUserIds = getRoomParticipantUserIds(message.getChatRoomId());
        String currentUserId = message.getUserId() != null ? message.getUserId() : "";
        boolean alreadyJoined = !currentUserId.isEmpty() && existingUserIds.contains(currentUserId);

        if (participantService.isRoomFull(message.getChatRoomId())) {
            if (!alreadyJoined) {
                ChatRoom room = chatRoomService.getRoom(message.getChatRoomId()).orElse(null);

                // DM(DIRECT) 방은 자동 분할하지 않음 -- 정원 초과 시 입장 거부
                if (room != null && room.getRoomType() == RoomType.DIRECT) {
                    log.warn("DM room {} is full, rejecting user {}", message.getChatRoomId(), message.getUsername());
                    messagingTemplate.convertAndSend(
                            "/topic/chat/" + message.getChatRoomId() + "/errors",
                            java.util.Map.of("type", "ROOM_FULL_DM", "roomId", message.getChatRoomId(),
                                    "roomName", room.getName()));
                    return;
                }

                String baseName = room != null ? room.getName().replaceAll("-\\d+$", "") : "일반";
                ChatRoom newRoom = participantService.findOrCreateAvailableRoom(baseName);

                log.info("Room {} full, redirecting user {} to {}",
                        message.getChatRoomId(), message.getUsername(), newRoom.getId());
                messagingTemplate.convertAndSend(
                        "/topic/chat/" + message.getChatRoomId() + "/errors",
                        java.util.Map.of("type", "ROOM_FULL", "redirectTo", newRoom.getId(), "roomName", newRoom.getName()));

                message.setChatRoomId(newRoom.getId());
            }
            // alreadyJoined이면 분기 통과 — 동일 방에 추가 entry만 등록 (sessionId 다름)
        }

        // Track participant in Redis SET using sessionId for multi-tab deduplication
        String participantKey = "chatflow:room:participants:" + message.getChatRoomId();
        String safeUserId = message.getUserId() != null ? message.getUserId() : "anonymous";
        String safeSessionId = sessionId != null ? sessionId : "unknown";
        String entry = safeUserId + ":" + safeSessionId + ":" + message.getUsername();
        redisTemplate.opsForSet().add(participantKey, entry);

        // Sync DB participantCount from Redis SET (unique user count)
        syncParticipantCount(message.getChatRoomId());

        if (alreadyJoined) {
            // 다른 세션(탭/디바이스)에서 같은 사용자가 재입장 — 시스템 JOIN/presence 노이즈 억제.
            // entry는 위에서 이미 추가됐고, count는 unique userId 기준이라 변동 없음.
            log.debug("User {} reconnected to room {} via additional session — suppressing JOIN broadcast",
                    message.getUsername(), message.getChatRoomId());
            return;
        }

        message.setType(ChatMessage.MessageType.JOIN);
        message.setTimestamp(LocalDateTime.now());
        message.setMessageId(UUID.randomUUID().toString());
        message.setContent(message.getUsername() + "님이 입장하셨습니다.");

        log.info("User {} joined chat room {}", message.getUsername(), message.getChatRoomId());

        // Broadcast presence JOIN event to room subscribers
        Set<String> participantIds = getRoomParticipantUserIds(message.getChatRoomId());
        messagingTemplate.convertAndSend("/topic/chat/" + message.getChatRoomId() + "/presence",
                java.util.Map.of(
                        "type", "JOIN",
                        "roomId", message.getChatRoomId(),
                        "username", message.getUsername(),
                        "participantCount", participantIds.size(),
                        "timestamp", LocalDateTime.now().toString()));

        chatPersistenceService.saveOutboxEventAndPublish(message, CHAT_TOPIC, "USER_JOINED");
    }

    public void join(ChatMessage message) {
        join(message, null);
    }

    public void leave(String roomId, String username, String sessionId) {
        String participantKey = "chatflow:room:participants:" + roomId;

        if (sessionId != null) {
            // Remove only the specific session entry
            Set<String> members = redisTemplate.opsForSet().members(participantKey);
            if (members != null) {
                members.stream()
                        .filter(e -> e.contains(":" + sessionId + ":"))
                        .forEach(e -> redisTemplate.opsForSet().remove(participantKey, e));
            }
        } else {
            // Fallback: remove all entries for this username
            Set<String> members = redisTemplate.opsForSet().members(participantKey);
            if (members != null) {
                members.stream()
                        .filter(e -> e.endsWith(":" + username))
                        .forEach(e -> redisTemplate.opsForSet().remove(participantKey, e));
            }
        }

        // Send LEAVE message only if the user has no remaining sessions in this room
        Set<String> remaining = redisTemplate.opsForSet().members(participantKey);
        boolean userStillPresent = remaining != null && remaining.stream()
                .anyMatch(e -> e.endsWith(":" + username));

        if (!userStillPresent) {
            ChatMessage leaveMessage = new ChatMessage();
            leaveMessage.setChatRoomId(roomId);
            leaveMessage.setUsername(username);
            leaveMessage.setType(ChatMessage.MessageType.LEAVE);
            leaveMessage.setTimestamp(LocalDateTime.now());
            leaveMessage.setMessageId(UUID.randomUUID().toString());
            leaveMessage.setContent(username + "님이 퇴장하셨습니다.");

            chatPersistenceService.saveOutboxEventAndPublish(leaveMessage, CHAT_TOPIC, "USER_LEFT");
            log.info("User {} left chat room {}", username, roomId);
        } else {
            log.info("User {} closed a tab in room {} (still has active sessions)", username, roomId);
        }

        syncParticipantCount(roomId);

        // Broadcast presence LEAVE event to room subscribers
        if (!userStillPresent) {
            Set<String> remainingUserIds = getRoomParticipantUserIds(roomId);
            messagingTemplate.convertAndSend("/topic/chat/" + roomId + "/presence",
                    java.util.Map.of(
                            "type", "LEAVE",
                            "roomId", roomId,
                            "username", username,
                            "participantCount", remainingUserIds.size(),
                            "timestamp", LocalDateTime.now().toString()));
        }
    }

    public void leave(String roomId, String username) {
        leave(roomId, username, null);
    }

    /**
     * Redis SET에서 roomId의 참여자 userId 집합을 반환한다.
     * entry format: "userId:sessionId:username" — 첫 segment(userId)만 추출, 중복 제거.
     */
    public Set<String> getRoomParticipantUserIds(String roomId) {
        String participantKey = "chatflow:room:participants:" + roomId;
        Set<String> members = redisTemplate.opsForSet().members(participantKey);
        if (members == null || members.isEmpty()) {
            return Set.of();
        }
        return members.stream()
                .map(e -> e.split(":")[0])
                .collect(java.util.stream.Collectors.toSet());
    }

    private void syncParticipantCount(String roomId) {
        Set<String> userIds = getRoomParticipantUserIds(roomId);
        participantService.setParticipantCount(roomId, userIds.size());
    }
}
