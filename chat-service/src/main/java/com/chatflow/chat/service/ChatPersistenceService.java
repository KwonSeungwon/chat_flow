package com.chatflow.chat.service;

import com.chatflow.chat.entity.ChatMessageEntity;
import com.chatflow.chat.exception.PersistenceException;
import com.chatflow.chat.entity.OutboxEvent;
import com.chatflow.chat.repository.ChatMessageRepository;
import com.chatflow.chat.repository.OutboxEventRepository;
import com.chatflow.common.dto.ChatMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatPersistenceService {

    private final ChatMessageRepository chatMessageRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    @Async("persistenceExecutor")
    @Transactional
    public void persistWithOutbox(ChatMessage message, String topic, String eventType) {
        ChatMessageEntity entity = ChatMessageEntity.builder()
                .messageId(message.getMessageId())
                .chatRoomId(message.getChatRoomId())
                .userId(message.getUserId())
                .username(message.getUsername())
                .content(message.getContent())
                .timestamp(message.getTimestamp())
                .type(message.getType() != null ? message.getType().name() : "CHAT")
                .isAiGenerated(message.isAiGenerated())
                .build();
        chatMessageRepository.save(entity);

        saveOutboxEvent(message, topic, eventType);
    }

    @Async("persistenceExecutor")
    @Transactional
    public void saveOutboxEvent(ChatMessage message, String topic, String eventType) {
        String payload = serializeMessage(message);
        OutboxEvent event = OutboxEvent.builder()
                .aggregateType("ChatMessage")
                .aggregateId(message.getMessageId())
                .eventType(eventType)
                .topic(topic)
                .partitionKey(message.getChatRoomId())
                .payload(payload)
                .build();
        outboxEventRepository.save(event);
    }

    private String serializeMessage(ChatMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            log.error("메시지 직렬화 실패: {}", message.getMessageId(), e);
            throw new PersistenceException("메시지 직렬화 실패", e);
        }
    }
}
