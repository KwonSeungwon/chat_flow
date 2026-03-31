package com.chatflow.chat.service;

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
    private final Counter messageCounter;

    public MessageSenderService(ChatPersistenceService chatPersistenceService, MeterRegistry registry) {
        this.chatPersistenceService = chatPersistenceService;
        this.messageCounter = Counter.builder("chatflow.messages.processed")
                .description("Total chat messages processed")
                .register(registry);
    }

    private static final String CHAT_TOPIC = "chat-messages";
    private static final String AI_SUMMARY_TOPIC = "ai-summary-requests";

    public void send(ChatMessage message) {
        message.setMessageId(UUID.randomUUID().toString());
        message.setTimestamp(LocalDateTime.now());

        log.info("Processing chat message: {}", message.getMessageId());

        String aiTopic = shouldRequestAISummary(message) ? AI_SUMMARY_TOPIC : null;
        chatPersistenceService.persistMessageAndPublish(message, CHAT_TOPIC, "MESSAGE_SENT", aiTopic);
        messageCounter.increment();
    }

    private boolean shouldRequestAISummary(ChatMessage message) {
        return message.getContent() != null && message.getContent().length() > 100;
    }
}
