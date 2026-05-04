package com.chatflow.chat.service;

import com.chatflow.chat.entity.ChatRoom;
import com.chatflow.chat.entity.RoomMemberEntity;
import com.chatflow.chat.entity.RoomType;
import com.chatflow.chat.repository.RoomMemberRepository;
import com.chatflow.common.dto.ChatMessage;
import com.chatflow.common.dto.KafkaTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserPresenceService {

    private final SimpMessagingTemplate messagingTemplate;
    private final ChatPersistenceService chatPersistenceService;
    private final ChatRoomService chatRoomService;
    private final ParticipantService participantService;
    private final StringRedisTemplate redisTemplate;
    private final RoomMemberRepository roomMemberRepository;
    private final RoomBanService roomBanService;

    public void join(ChatMessage message, String sessionId) {
        String currentUserId = message.getUserId() != null ? message.getUserId() : "";

        if (checkBanGate(currentUserId, message.getChatRoomId(), message.getUsername())) {
            return;
        }

        Set<String> existingUserIds = getRoomParticipantUserIds(message.getChatRoomId());
        boolean alreadyJoined = !currentUserId.isEmpty() && existingUserIds.contains(currentUserId);

        if (handleRoomFullIfNeeded(message, currentUserId, alreadyJoined)) {
            return;
        }

        registerParticipant(message, sessionId);

        if (alreadyJoined) {
            log.debug("User {} reconnected to room {} via additional session — suppressing JOIN broadcast",
                    message.getUsername(), message.getChatRoomId());
            return;
        }

        broadcastJoin(message);
    }

    /**
     * @return true if the user is banned and join should be aborted
     */
    private boolean checkBanGate(String userId, String chatRoomId, String username) {
        if (!userId.isEmpty() && roomBanService.isBanned(chatRoomId, userId)) {
            log.warn("User {} attempted to join banned room {}", username, chatRoomId);
            messagingTemplate.convertAndSend(
                    "/topic/chat/" + chatRoomId + "/errors",
                    Map.of("type", "ROOM_BANNED",
                            "roomId", chatRoomId));
            return true;
        }
        return false;
    }

    /**
     * 만석인 경우 처리. DM은 기존 멤버만 허용, 일반 방은 redirect.
     * @return true if join should be aborted (non-member DM full)
     */
    private boolean handleRoomFullIfNeeded(ChatMessage message, String currentUserId, boolean alreadyJoined) {
        if (!participantService.isRoomFull(message.getChatRoomId())) {
            return false;
        }
        if (alreadyJoined) {
            return false;
        }

        ChatRoom room = chatRoomService.getRoom(message.getChatRoomId()).orElse(null);

        if (room != null && room.getRoomType() == RoomType.DIRECT) {
            boolean isExistingMember = !currentUserId.isEmpty() &&
                    roomMemberRepository.existsByRoomIdAndUserId(
                            message.getChatRoomId(), currentUserId);
            if (!isExistingMember) {
                log.warn("DM room {} is full, rejecting non-member {}",
                        message.getChatRoomId(), message.getUsername());
                messagingTemplate.convertAndSend(
                        "/topic/chat/" + message.getChatRoomId() + "/errors",
                        Map.of("type", "ROOM_FULL_DM",
                                "roomId", message.getChatRoomId(),
                                "roomName", room.getName()));
                return true;
            }
            log.info("DM {} full but {} is existing member — allowing re-entry",
                    message.getChatRoomId(), message.getUsername());
        } else {
            String baseName = room != null ? room.getName().replaceAll("-\\d+$", "") : "일반";
            ChatRoom newRoom = participantService.findOrCreateAvailableRoom(baseName);

            log.info("Room {} full, redirecting user {} to {}",
                    message.getChatRoomId(), message.getUsername(), newRoom.getId());
            messagingTemplate.convertAndSend(
                    "/topic/chat/" + message.getChatRoomId() + "/errors",
                    Map.of("type", "ROOM_FULL", "redirectTo", newRoom.getId(), "roomName", newRoom.getName()));

            message.setChatRoomId(newRoom.getId());
        }
        return false;
    }

    private void registerParticipant(ChatMessage message, String sessionId) {
        String participantKey = "chatflow:room:participants:" + message.getChatRoomId();
        String safeUserId = message.getUserId() != null ? message.getUserId() : "anonymous";
        String safeSessionId = sessionId != null ? sessionId : "unknown";
        String entry = safeUserId + ":" + safeSessionId + ":" + message.getUsername();

        redisTemplate.opsForSet().add(participantKey, entry);
        redisTemplate.expire(participantKey, 7, TimeUnit.DAYS);
        syncParticipantCount(message.getChatRoomId());

        if (!safeUserId.equals("anonymous") &&
                !roomMemberRepository.existsByRoomIdAndUserId(message.getChatRoomId(), safeUserId)) {
            try {
                String safeUsername = message.getUsername() != null ? message.getUsername() : "anonymous";
                roomMemberRepository.save(RoomMemberEntity.builder()
                        .roomId(message.getChatRoomId())
                        .userId(safeUserId)
                        .username(safeUsername)
                        .joinedAt(LocalDateTime.now())
                        .build());
            } catch (Exception e) {
                log.debug("Failed to register room membership: roomId={} userId={} reason={}",
                        message.getChatRoomId(), safeUserId, e.getMessage());
            }
        }
    }

    private void broadcastJoin(ChatMessage message) {
        message.setType(ChatMessage.MessageType.JOIN);
        message.setTimestamp(LocalDateTime.now());
        message.setMessageId(UUID.randomUUID().toString());
        message.setContent(message.getUsername() + "님이 입장하셨습니다.");

        log.info("User {} joined chat room {}", message.getUsername(), message.getChatRoomId());

        Set<String> participantIds = getRoomParticipantUserIds(message.getChatRoomId());
        messagingTemplate.convertAndSend("/topic/chat/" + message.getChatRoomId() + "/presence",
                Map.of("type", "JOIN",
                        "roomId", message.getChatRoomId(),
                        "username", message.getUsername(),
                        "participantCount", participantIds.size(),
                        "timestamp", LocalDateTime.now().toString()));

        chatPersistenceService.saveOutboxEventAndPublish(message, KafkaTopics.CHAT_MESSAGES, "USER_JOINED");
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

            chatPersistenceService.saveOutboxEventAndPublish(leaveMessage, KafkaTopics.CHAT_MESSAGES, "USER_LEFT");
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
                .map(e -> e.split(":", 3)[0])
                .collect(java.util.stream.Collectors.toSet());
    }

    private void syncParticipantCount(String roomId) {
        Set<String> userIds = getRoomParticipantUserIds(roomId);
        participantService.setParticipantCount(roomId, userIds.size());
    }
}
