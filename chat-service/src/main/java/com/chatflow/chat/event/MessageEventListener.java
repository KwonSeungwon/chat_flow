package com.chatflow.chat.event;

import com.chatflow.chat.service.UserPresenceService;
import com.chatflow.chat.service.message.MentionTargets;
import com.chatflow.common.dto.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.util.List;
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

        // W1: Send per-user unread increment notification (exclude sender). Server-authored
        // JOIN/LEAVE/SYSTEM text is not something anyone needs to catch up on, and the badge
        // rule has to agree with the mention rows — hence the shared predicate.
        if (MentionTargets.carriesUserText(message.getType())) {
            sendUnreadNotifications(message, event.getMentionedUsernames());
        }
    }

    /**
     * @param mentioned 발행 시점에 확정된 멘션 대상. 본문을 다시 파싱하지 않는다 —
     *                  사용자명 문자셋이 자유로워서 텍스트만으로는 대상을 알 수 없고,
     *                  멘션 행/FCM과 같은 목록을 써야 클라이언트 배지가 어긋나지 않는다.
     */
    private void sendUnreadNotifications(ChatMessage message, List<String> mentioned) {
        try {
            Set<String> participantUserIds = userPresenceService.getRoomParticipantUserIds(message.getChatRoomId());
            String senderId = message.getUserId();

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

}
