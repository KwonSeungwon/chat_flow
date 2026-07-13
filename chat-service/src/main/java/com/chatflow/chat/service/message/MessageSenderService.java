package com.chatflow.chat.service.message;

import com.chatflow.chat.service.outbox.ChatPersistenceService;

import com.chatflow.chat.entity.MessageMentionEntity;
import com.chatflow.chat.entity.RoomMemberEntity;
import com.chatflow.chat.repository.ChatMessageRepository;
import com.chatflow.chat.repository.RoomMemberRepository;
import com.chatflow.chat.service.notification.FcmNotificationService;
import com.chatflow.chat.service.room.ChatRoomService;
import com.chatflow.common.dto.BaseMessage.MessageType;
import com.chatflow.common.dto.ChatMessage;
import com.chatflow.common.dto.KafkaTopics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class MessageSenderService {

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

    /**
     * Sends a message, fetching the room member from the DB for the mute check.
     * Used by callers that have NOT already resolved the member (REST, scheduled).
     */
    public void send(ChatMessage message) {
        RoomMemberEntity member = null;
        if (MessageType.CHAT.equals(message.getType()) && message.getUserId() != null) {
            member = roomMemberRepository.findByRoomIdAndUserId(
                    message.getChatRoomId(), message.getUserId()).orElse(null);
        }
        send(message, member);
    }

    /**
     * Sends a message using a pre-resolved room member entity for the mute check.
     * Called by the STOMP send path (via {@code ChatService.processMessage}) where
     * the membership check already fetched the entity, and by the single-arg
     * {@link #send(ChatMessage)} overload after self-fetching.
     *
     * @param resolvedMember the pre-fetched member entity used solely for the mute
     *                       gate. {@code null} means "no mute info available" — the
     *                       mute gate is skipped (correct for legacy creator-only
     *                       membership where no room_members row exists, and for
     *                       non-CHAT message types).
     */
    public void send(ChatMessage message, RoomMemberEntity resolvedMember) {
        // Mute gate — muted users cannot send CHAT messages
        if (MessageType.CHAT.equals(message.getType()) && message.getUserId() != null) {
            // mutedUntil == now ⇒ 만료 (mute가 끝나는 그 순간부터는 발송 허용)
            if (resolvedMember != null && resolvedMember.getMutedUntil() != null
                    && resolvedMember.getMutedUntil().isAfter(LocalDateTime.now())) {
                log.warn("Muted user {} tried to send message to room {}",
                        message.getUsername(), message.getChatRoomId());
                messagingTemplate.convertAndSendToUser(
                        message.getUserId(),
                        "/queue/errors",
                        Map.of("type", "MUTED",
                                "roomId", message.getChatRoomId(),
                                "mutedUntil", resolvedMember.getMutedUntil().toString()));
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

        // Resolve mentions for CHAT messages: one DB lookup, reused for rows + FCM
        List<RoomMemberEntity> mentionedMembers = List.of();
        List<MessageMentionEntity> mentionEntities = List.of();
        if (MessageType.CHAT.equals(message.getType())) {
            List<String> candidates = MentionExtractor.extract(message.getContent());
            if (!candidates.isEmpty()) {
                mentionedMembers = roomMemberRepository
                        .findByRoomIdAndUsernameIn(message.getChatRoomId(), candidates)
                        .stream()
                        .filter(m -> !m.getUsername().equals(message.getUsername()))
                        .toList();
                mentionEntities = mentionedMembers.stream()
                        .map(m -> MessageMentionEntity.builder()
                                .messageId(message.getMessageId())
                                .roomId(message.getChatRoomId())
                                .mentionedUserId(m.getUserId())
                                .mentionedUsername(m.getUsername())
                                .fromUsername(message.getUsername())
                                .createdAt(message.getTimestamp())
                                .read(false)
                                .build())
                        .toList();
            }
        }

        String aiTopic = shouldRequestAISummary(message) ? KafkaTopics.AI_SUMMARY_REQUESTS : null;
        chatPersistenceService.persistMessageAndPublish(
                message, KafkaTopics.CHAT_MESSAGES, "MESSAGE_SENT", aiTopic, mentionEntities);
        messageCounter.increment();
        chatRoomService.updateLastMessageAt(message.getChatRoomId());

        if (MessageType.CHAT.equals(message.getType())) {
            fcmNotificationService.sendMessageNotification(
                message.getChatRoomId(), message.getUsername(), message.getContent());
            // Send mention notifications only to resolved room members (not raw candidates)
            for (RoomMemberEntity mentioned : mentionedMembers) {
                fcmNotificationService.sendMessageNotification(
                    "mention-" + mentioned.getUsername(), message.getUsername(),
                    message.getUsername() + "님이 회원님을 멘션했습니다: " + message.getContent());
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
        if (message.getType() != MessageType.CHAT) return false;
        return message.getContent() != null && message.getContent().length() > 100;
    }

    // Test seam — package-private to avoid making the rule public.
    boolean shouldRequestAISummaryForTest(ChatMessage message) {
        return shouldRequestAISummary(message);
    }

    private String buildPreview(String username, String content) {
        if (content == null) content = "";
        String truncated = content.length() > 50
                ? content.substring(0, 50) + "..."
                : content;
        return username + ": " + truncated;
    }
}
