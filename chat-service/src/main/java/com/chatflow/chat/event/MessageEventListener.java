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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class MessageEventListener {

    private static final Pattern MENTION_PATTERN = Pattern.compile("@([A-Za-z0-9_\\.\\uac00-\\ud7a3]{1,30})");

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
            List<String> mentioned = extractMentionedUsernames(message.getContent());

            // Truncate content to 200 chars for keyword-matching on the client.
            // FILE messages may have empty content — fall back to empty string.
            String content = message.getContent() != null ? message.getContent() : "";
            if (content.length() > 200) content = content.substring(0, 200);

            Map<String, Object> payload = Map.of(
                    "type", "UNREAD_INCREMENT",
                    "roomId", message.getChatRoomId(),
                    "senderId", senderId != null ? senderId : "",
                    "senderUsername", message.getUsername() != null ? message.getUsername() : "",
                    "content", content,
                    "mentionedUsernames", mentioned,
                    "timestamp", LocalDateTime.now().toString());

            for (String userId : participantUserIds) {
                if (!userId.equals(senderId)) {
                    messagingTemplate.convertAndSendToUser(userId, "/queue/room-updates", payload);
                }
            }
            log.debug("Sent UNREAD_INCREMENT to {} participants (mentions={}) for room {}",
                    participantUserIds.size() - 1, mentioned.size(), message.getChatRoomId());
        } catch (Exception e) {
            log.warn("Failed to send unread notifications for message {}: {}",
                    message.getMessageId(), e.getMessage());
        }
    }

    private static List<String> extractMentionedUsernames(String content) {
        if (content == null || content.isEmpty() || content.indexOf('@') < 0) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        Matcher m = MENTION_PATTERN.matcher(content);
        while (m.find()) {
            result.add(m.group(1));
        }
        return result;
    }
}
