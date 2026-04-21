package com.chatflow.chat.event;

import com.chatflow.chat.service.UserPresenceService;
import com.chatflow.common.dto.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class MessageEventListener {

    private final SimpMessagingTemplate messagingTemplate;
    private final UserPresenceService userPresenceService;

    @Async("persistenceExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMessagePersisted(MessagePersistedEvent event) {
        ChatMessage message = event.getMessage();
        messagingTemplate.convertAndSend("/topic/chat/" + message.getChatRoomId(), message);
        log.debug("Broadcast {} to room {} after commit", message.getMessageId(), message.getChatRoomId());

        // W1: Send per-user unread increment notification (exclude sender, skip JOIN/LEAVE)
        if (message.getType() == ChatMessage.MessageType.CHAT
                || message.getType() == ChatMessage.MessageType.FILE) {
            sendUnreadNotifications(message);
        }
    }

    private void sendUnreadNotifications(ChatMessage message) {
        try {
            Set<String> participantUserIds = userPresenceService.getRoomParticipantUserIds(message.getChatRoomId());
            String senderId = message.getUserId();

            Map<String, Object> payload = Map.of(
                    "type", "UNREAD_INCREMENT",
                    "roomId", message.getChatRoomId(),
                    "senderId", senderId != null ? senderId : "",
                    "timestamp", LocalDateTime.now().toString());

            for (String userId : participantUserIds) {
                if (!userId.equals(senderId)) {
                    messagingTemplate.convertAndSendToUser(userId, "/queue/room-updates", payload);
                }
            }
            log.debug("Sent UNREAD_INCREMENT to {} participants for room {}",
                    participantUserIds.size() - 1, message.getChatRoomId());
        } catch (Exception e) {
            log.warn("Failed to send unread notifications for message {}: {}",
                    message.getMessageId(), e.getMessage());
        }
    }
}
