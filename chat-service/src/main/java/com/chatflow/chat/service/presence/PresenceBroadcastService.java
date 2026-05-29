package com.chatflow.chat.service.presence;

import com.chatflow.chat.service.outbox.ChatPersistenceService;
import com.chatflow.common.dto.ChatMessage;
import com.chatflow.common.dto.KafkaTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * STOMP presence broadcasts (JOIN / LEAVE topics) and the corresponding
 * outbox events for downstream consumers.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PresenceBroadcastService {

    private final SimpMessagingTemplate messagingTemplate;
    private final ChatPersistenceService chatPersistenceService;

    public void broadcastJoin(ChatMessage message, int participantCount) {
        message.setType(ChatMessage.MessageType.JOIN);
        message.setTimestamp(LocalDateTime.now());
        message.setMessageId(UUID.randomUUID().toString());
        message.setContent(message.getUsername() + "님이 입장하셨습니다.");

        log.info("User {} joined chat room {}", message.getUsername(), message.getChatRoomId());

        messagingTemplate.convertAndSend("/topic/chat/" + message.getChatRoomId() + "/presence",
                Map.of("type", "JOIN",
                        "roomId", message.getChatRoomId(),
                        "username", message.getUsername(),
                        "participantCount", participantCount,
                        "timestamp", LocalDateTime.now().toString()));

        chatPersistenceService.saveOutboxEventAndPublish(message, KafkaTopics.CHAT_MESSAGES, "USER_JOINED");
    }

    public void broadcastLeave(String roomId, String username, int participantCount) {
        messagingTemplate.convertAndSend("/topic/chat/" + roomId + "/presence",
                Map.of("type", "LEAVE",
                        "roomId", roomId,
                        "username", username,
                        "participantCount", participantCount,
                        "timestamp", LocalDateTime.now().toString()));
    }

    public void persistLeaveEvent(String roomId, String username) {
        ChatMessage leaveMessage = new ChatMessage();
        leaveMessage.setChatRoomId(roomId);
        leaveMessage.setUsername(username);
        leaveMessage.setType(ChatMessage.MessageType.LEAVE);
        leaveMessage.setTimestamp(LocalDateTime.now());
        leaveMessage.setMessageId(UUID.randomUUID().toString());
        leaveMessage.setContent(username + "님이 퇴장하셨습니다.");

        chatPersistenceService.saveOutboxEventAndPublish(leaveMessage, KafkaTopics.CHAT_MESSAGES, "USER_LEFT");
        log.info("User {} left chat room {}", username, roomId);
    }
}
