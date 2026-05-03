package com.chatflow.chat.service;

import com.chatflow.chat.entity.RoomMemberEntity;
import com.chatflow.chat.repository.ChatMessageRepository;
import com.chatflow.chat.repository.RoomMemberRepository;
import com.chatflow.common.dto.BaseMessage.MessageType;
import com.chatflow.common.dto.ChatMessage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class MessageSenderService {

    private static final java.util.regex.Pattern MENTION_PATTERN = java.util.regex.Pattern.compile("@(\\S+)");

    private final ChatPersistenceService chatPersistenceService;
    private final ChatRoomService chatRoomService;
    private final FcmNotificationService fcmNotificationService;
    private final ChatMessageRepository chatMessageRepository;
    private final RoomMemberRepository roomMemberRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final Counter messageCounter;

    public MessageSenderService(ChatPersistenceService chatPersistenceService,
                                ChatRoomService chatRoomService,
                                FcmNotificationService fcmNotificationService,
                                ChatMessageRepository chatMessageRepository,
                                RoomMemberRepository roomMemberRepository,
                                SimpMessagingTemplate messagingTemplate,
                                MeterRegistry registry) {
        this.chatPersistenceService = chatPersistenceService;
        this.chatRoomService = chatRoomService;
        this.fcmNotificationService = fcmNotificationService;
        this.chatMessageRepository = chatMessageRepository;
        this.roomMemberRepository = roomMemberRepository;
        this.messagingTemplate = messagingTemplate;
        this.messageCounter = Counter.builder("chatflow.messages.processed")
                .description("Total chat messages processed")
                .register(registry);
    }

    private static final String CHAT_TOPIC = "chat-messages";
    private static final String AI_SUMMARY_TOPIC = "ai-summary-requests";

    public void send(ChatMessage message) {
        // Mute gate — muted users cannot send CHAT messages
        if (MessageType.CHAT.equals(message.getType()) && message.getUserId() != null) {
            RoomMemberEntity member = roomMemberRepository.findByRoomIdAndUserId(
                    message.getChatRoomId(), message.getUserId()).orElse(null);
            // mutedUntil == now ⇒ 만료 (mute가 끝나는 그 순간부터는 발송 허용)
            if (member != null && member.getMutedUntil() != null
                    && member.getMutedUntil().isAfter(LocalDateTime.now())) {
                log.warn("Muted user {} tried to send message to room {}",
                        message.getUsername(), message.getChatRoomId());
                messagingTemplate.convertAndSendToUser(
                        message.getUserId(),
                        "/queue/errors",
                        Map.of("type", "MUTED",
                                "roomId", message.getChatRoomId(),
                                "mutedUntil", member.getMutedUntil().toString()));
                return;
            }
        }

        message.setMessageId(UUID.randomUUID().toString());
        message.setTimestamp(LocalDateTime.now());

        // Reply validation & preview generation
        if (message.getParentMessageId() != null && !message.getParentMessageId().isBlank()) {
            chatMessageRepository.findById(message.getParentMessageId())
                    .ifPresentOrElse(parent -> {
                        // Enforce 1-level depth: reply-to-reply redirects to root
                        if (parent.getParentMessageId() != null) {
                            message.setParentMessageId(parent.getParentMessageId());
                            chatMessageRepository.findById(parent.getParentMessageId())
                                    .ifPresent(root -> message.setParentMessagePreview(
                                            buildPreview(root.getUsername(), root.getContent())));
                        } else {
                            message.setParentMessagePreview(
                                    buildPreview(parent.getUsername(), parent.getContent()));
                        }
                    }, () -> {
                        // Parent not found — clear reference
                        message.setParentMessageId(null);
                        message.setParentMessagePreview(null);
                    });
        }

        // Enrich message with room metadata
        chatRoomService.getRoom(message.getChatRoomId()).ifPresent(room -> {
            message.setRoomType(room.getRoomType() != null ? room.getRoomType().name() : "GENERAL");
        });

        log.info("Processing chat message: {}", message.getMessageId());

        String aiTopic = shouldRequestAISummary(message) ? AI_SUMMARY_TOPIC : null;
        chatPersistenceService.persistMessageAndPublish(message, CHAT_TOPIC, "MESSAGE_SENT", aiTopic);
        messageCounter.increment();
        chatRoomService.updateLastMessageAt(message.getChatRoomId());

        if (MessageType.CHAT.equals(message.getType())) {
            fcmNotificationService.sendMessageNotification(
                message.getChatRoomId(), message.getUsername(), message.getContent());
            // Parse @mentions and send targeted notifications
            var mentionPattern = MENTION_PATTERN;
            var matcher = mentionPattern.matcher(message.getContent());
            while (matcher.find()) {
                String mentionedUser = matcher.group(1);
                if (!mentionedUser.equals(message.getUsername())) {
                    fcmNotificationService.sendMessageNotification(
                        "mention-" + mentionedUser, message.getUsername(),
                        message.getUsername() + "님이 회원님을 멘션했습니다: " + message.getContent());
                }
            }
        } else if (MessageType.FILE.equals(message.getType())) {
            String notifContent = message.getFileName() != null
                    ? "파일을 보냈습니다: " + message.getFileName()
                    : "파일을 보냈습니다";
            fcmNotificationService.sendMessageNotification(
                message.getChatRoomId(), message.getUsername(), notifContent);
        }
    }

    private boolean shouldRequestAISummary(ChatMessage message) {
        if (MessageType.FILE.equals(message.getType())) return false;
        return message.getContent() != null && message.getContent().length() > 100;
    }

    private String buildPreview(String username, String content) {
        if (content == null) content = "";
        String truncated = content.length() > 50
                ? content.substring(0, 50) + "..."
                : content;
        return username + ": " + truncated;
    }
}
