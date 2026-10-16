package com.chatflow.chat.service.message;

import com.chatflow.chat.service.outbox.ChatPersistenceService;

import com.chatflow.chat.dto.InboundChatCommand;
import com.chatflow.chat.entity.ChatMessageEntity;
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
import java.util.Objects;
import java.util.Optional;
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
        if (isUserSendable(message.getType()) && message.getUserId() != null) {
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
     *                       mute gate is skipped (no room_members row exists).
     */
    public void send(ChatMessage message, RoomMemberEntity resolvedMember) {
        // Every caller of send() is a user-authored path (STOMP, REST, scheduled).
        // Server-authored types (JOIN/LEAVE/SYSTEM/AI_SUMMARY) have their own
        // publishers and must never be forgeable through here.
        if (!isUserSendable(message.getType())) {
            log.warn("Rejected non-user message type {} in room {}",
                    message.getType(), message.getChatRoomId());
            if (message.getUserId() != null) {
                messagingTemplate.convertAndSendToUser(
                        message.getUserId(),
                        "/queue/errors",
                        Map.of("type", "INVALID_MESSAGE_TYPE",
                                "roomId", message.getChatRoomId() != null ? message.getChatRoomId() : ""));
            }
            return;
        }
        message.setAiGenerated(false);

        // Mute gate — muted users cannot post anything they authored, whatever the
        // type: a caption (FILE) fires mention pushes and a PATIENT_CARD lands in
        // the room just like text, so every user-sendable type is gated.
        if (message.getUserId() != null) {
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

        // Reply validation & preview generation. The parent (and the root it
        // redirects to) must live in this room — otherwise a reply could leak a
        // preview of a message from a room the sender cannot read.
        if (message.getParentMessageId() != null && !message.getParentMessageId().isBlank()) {
            resolveReplyTarget(message);
        }

        // Enrich message with room metadata
        chatRoomService.getRoom(message.getChatRoomId()).ifPresent(room -> {
            message.setRoomType(room.getRoomType() != null ? room.getRoomType().name() : "GENERAL");
        });

        log.info("Processing chat message: {}", message.getMessageId());

        // Resolve mentions once — the rows, the FCM pings and the persisted event all
        // reuse this single room_members lookup.
        List<RoomMemberEntity> mentionedMembers = List.of();
        List<MessageMentionEntity> mentionEntities = List.of();
        if (MentionTargets.shouldResolveMentions(message)) {
            mentionedMembers = MentionTargets.resolve(
                    roomMemberRepository.findByRoomId(message.getChatRoomId()),
                    message.getContent(), message.getUsername());
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

        String aiTopic = shouldRequestAISummary(message) ? KafkaTopics.AI_SUMMARY_REQUESTS : null;
        chatPersistenceService.persistMessageAndPublish(
                message, KafkaTopics.CHAT_MESSAGES, "MESSAGE_SENT", aiTopic, mentionEntities);
        messageCounter.increment();
        chatRoomService.updateLastMessageAt(message.getChatRoomId());

        if (MessageType.CHAT.equals(message.getType())) {
            fcmNotificationService.sendMessageNotification(
                message.getChatRoomId(), message.getUsername(), message.getContent());
        } else if (MessageType.FILE.equals(message.getType())) {
            String notifContent = message.getFileName() != null
                    ? "파일을 보냈습니다: " + message.getFileName()
                    : "파일을 보냈습니다";
            fcmNotificationService.sendMessageNotification(
                message.getChatRoomId(), message.getUsername(), notifContent);
        }

        // Mention pings ride on the caption too, so this sits outside the type branch.
        // mentionedMembers is empty for every type that cannot carry user text.
        for (RoomMemberEntity mentioned : mentionedMembers) {
            fcmNotificationService.sendMessageNotification(
                "mention-" + mentioned.getUsername(), message.getUsername(),
                message.getUsername() + "님이 회원님을 멘션했습니다: " + message.getContent());
        }
    }

    private static boolean isUserSendable(MessageType type) {
        return InboundChatCommand.ALLOWED_TYPES.contains(type);
    }

    private void resolveReplyTarget(ChatMessage message) {
        Optional<ChatMessageEntity> parent = findInRoom(message.getParentMessageId(), message.getChatRoomId());
        if (parent.isEmpty()) {
            clearReply(message);
            return;
        }
        // Enforce 1-level depth: reply-to-reply redirects to root
        String rootId = parent.get().getParentMessageId();
        if (rootId == null) {
            message.setParentMessagePreview(
                    buildPreview(parent.get().getUsername(), parent.get().getContent()));
            return;
        }
        findInRoom(rootId, message.getChatRoomId()).ifPresentOrElse(root -> {
            message.setParentMessageId(root.getMessageId());
            message.setParentMessagePreview(buildPreview(root.getUsername(), root.getContent()));
        }, () -> clearReply(message));
    }

    private Optional<ChatMessageEntity> findInRoom(String messageId, String roomId) {
        return chatMessageRepository.findById(messageId)
                .filter(m -> Objects.equals(m.getChatRoomId(), roomId));
    }

    private static void clearReply(ChatMessage message) {
        message.setParentMessageId(null);
        message.setParentMessagePreview(null);
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
