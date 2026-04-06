package com.chatflow.chat.service;

import com.chatflow.common.dto.BaseMessage.MessageType;
import com.chatflow.common.dto.ChatMessage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
public class MessageSenderService {

    private final ChatPersistenceService chatPersistenceService;
    private final ChatRoomService chatRoomService;
    private final FcmNotificationService fcmNotificationService;
    private final Counter messageCounter;

    public MessageSenderService(ChatPersistenceService chatPersistenceService,
                                ChatRoomService chatRoomService,
                                FcmNotificationService fcmNotificationService,
                                MeterRegistry registry) {
        this.chatPersistenceService = chatPersistenceService;
        this.chatRoomService = chatRoomService;
        this.fcmNotificationService = fcmNotificationService;
        this.messageCounter = Counter.builder("chatflow.messages.processed")
                .description("Total chat messages processed")
                .register(registry);
    }

    private static final String CHAT_TOPIC = "chat-messages";
    private static final String AI_SUMMARY_TOPIC = "ai-summary-requests";

    public void send(ChatMessage message) {
        message.setMessageId(UUID.randomUUID().toString());
        message.setTimestamp(LocalDateTime.now());

        // Enrich message with room metadata
        chatRoomService.getRoom(message.getChatRoomId()).ifPresent(room -> {
            message.setRoomType(room.getRoomType() != null ? room.getRoomType().name() : "GENERAL");
        });

        log.info("Processing chat message: {}", message.getMessageId());

        String aiTopic = shouldRequestAISummary(message) ? AI_SUMMARY_TOPIC : null;
        chatPersistenceService.persistMessageAndPublish(message, CHAT_TOPIC, "MESSAGE_SENT", aiTopic);
        messageCounter.increment();

        if (MessageType.CHAT.equals(message.getType())) {
            fcmNotificationService.sendMessageNotification(
                message.getChatRoomId(), message.getUsername(), message.getContent());
        }
    }

    private boolean shouldRequestAISummary(ChatMessage message) {
        return message.getContent() != null && message.getContent().length() > 100;
    }
}
