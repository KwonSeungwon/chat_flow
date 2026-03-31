package com.chatflow.chat.service;

import com.chatflow.chat.entity.ChatMessageEntity;
import com.chatflow.chat.entity.OutboxEvent;
import com.chatflow.chat.event.MessagePersistedEvent;
import com.chatflow.chat.exception.PersistenceException;
import com.chatflow.chat.repository.ChatMessageRepository;
import com.chatflow.chat.repository.OutboxEventRepository;
import com.chatflow.common.dto.ChatMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatPersistenceService {

    private final ChatMessageRepository chatMessageRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 메시지 + Outbox 이벤트를 단일 TX로 영속화.
     * TX 커밋 후 MessagePersistedEvent → WebSocket 브로드캐스트.
     *
     * @param aiSummaryTopic null이면 AI 요약 요청 생략
     */
    @Transactional
    public void persistMessageAndPublish(ChatMessage message, String chatTopic, String eventType,
                                         String aiSummaryTopic) {
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

        saveOutboxEventInternal(message, chatTopic, eventType);

        if (aiSummaryTopic != null) {
            saveOutboxEventInternal(message, aiSummaryTopic, "AI_SUMMARY_REQUEST");
        }

        eventPublisher.publishEvent(new MessagePersistedEvent(message));
    }

    /**
     * Outbox 이벤트만 저장 + TX 커밋 후 브로드캐스트 (JOIN/LEAVE 등).
     */
    @Transactional
    public void saveOutboxEventAndPublish(ChatMessage message, String topic, String eventType) {
        saveOutboxEventInternal(message, topic, eventType);
        eventPublisher.publishEvent(new MessagePersistedEvent(message));
    }

    /**
     * Outbox 이벤트만 저장 (브로드캐스트 불필요 시).
     */
    @Transactional
    public void saveOutboxEvent(ChatMessage message, String topic, String eventType) {
        saveOutboxEventInternal(message, topic, eventType);
    }

    private void saveOutboxEventInternal(ChatMessage message, String topic, String eventType) {
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
