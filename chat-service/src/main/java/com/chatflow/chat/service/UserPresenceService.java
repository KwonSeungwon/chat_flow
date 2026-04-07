package com.chatflow.chat.service;

import com.chatflow.chat.entity.ChatRoom;
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
    private final StringRedisTemplate redisTemplate;

    private static final String CHAT_TOPIC = "chat-messages";

    public void join(ChatMessage message) {
        if (chatRoomService.isRoomFull(message.getChatRoomId())) {
            ChatRoom room = chatRoomService.getRoom(message.getChatRoomId()).orElse(null);
            String baseName = room != null ? room.getName().replaceAll("-\\d+$", "") : "일반";
            ChatRoom newRoom = chatRoomService.findOrCreateAvailableRoom(baseName);

            log.info("Room {} full, redirecting user {} to {}",
                    message.getChatRoomId(), message.getUsername(), newRoom.getId());
            messagingTemplate.convertAndSend(
                    "/topic/chat/" + message.getChatRoomId() + "/errors",
                    java.util.Map.of("type", "ROOM_FULL", "redirectTo", newRoom.getId(), "roomName", newRoom.getName()));

            message.setChatRoomId(newRoom.getId());
        }

        message.setType(ChatMessage.MessageType.JOIN);
        message.setTimestamp(LocalDateTime.now());
        message.setMessageId(UUID.randomUUID().toString());
        message.setContent(message.getUsername() + "님이 입장하셨습니다.");

        log.info("User {} joined chat room {}", message.getUsername(), message.getChatRoomId());

        chatRoomService.incrementParticipantCount(message.getChatRoomId());

        // Track participant in Redis SET (no TTL — rely on leave() for cleanup)
        String participantKey = "chatflow:room:participants:" + message.getChatRoomId();
        String safeUserId = message.getUserId() != null ? message.getUserId() : "anonymous";
        String entry = safeUserId + ":" + message.getUsername();
        redisTemplate.opsForSet().add(participantKey, entry);

        chatPersistenceService.saveOutboxEventAndPublish(message, CHAT_TOPIC, "USER_JOINED");
    }

    public void leave(String roomId, String username) {
        ChatMessage leaveMessage = new ChatMessage();
        leaveMessage.setChatRoomId(roomId);
        leaveMessage.setUsername(username);
        leaveMessage.setType(ChatMessage.MessageType.LEAVE);
        leaveMessage.setTimestamp(LocalDateTime.now());
        leaveMessage.setMessageId(UUID.randomUUID().toString());
        leaveMessage.setContent(username + "님이 퇴장하셨습니다.");

        chatRoomService.decrementParticipantCount(roomId);

        String participantKey = "chatflow:room:participants:" + roomId;
        // Remove entries where username matches (userId unknown at disconnect)
        Set<String> members = redisTemplate.opsForSet().members(participantKey);
        if (members != null) {
            members.stream()
                    .filter(e -> e.endsWith(":" + username))
                    .forEach(e -> redisTemplate.opsForSet().remove(participantKey, e));
        }

        chatPersistenceService.saveOutboxEventAndPublish(leaveMessage, CHAT_TOPIC, "USER_LEFT");
        log.info("User {} left chat room {}", username, roomId);
    }
}
