package com.chatflow.chat.service.outbox;

import com.chatflow.chat.entity.ChatMessageEntity;
import com.chatflow.chat.entity.MessageMentionEntity;
import com.chatflow.chat.entity.OutboxEvent;
import com.chatflow.chat.event.MessagePersistedEvent;
import com.chatflow.chat.exception.PersistenceException;
import com.chatflow.chat.mapper.ChatMessageMapper;
import com.chatflow.chat.repository.ChatMessageRepository;
import com.chatflow.chat.repository.MessageMentionRepository;
import com.chatflow.chat.repository.OutboxEventRepository;
import com.chatflow.common.dto.ChatMessage;
import com.chatflow.common.util.MessageEncryptor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatPersistenceService {

    private final ChatMessageRepository chatMessageRepository;
    private final MessageMentionRepository messageMentionRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final MessageEncryptor messageEncryptor;
    private final ChatMessageMapper chatMessageMapper;

    /**
     * 메시지 + Outbox 이벤트를 단일 TX로 영속화.
     * TX 커밋 후 MessagePersistedEvent → WebSocket 브로드캐스트.
     *
     * @param aiSummaryTopic null이면 AI 요약 요청 생략
     */
    @Transactional
    public void persistMessageAndPublish(ChatMessage message, String chatTopic, String eventType,
                                         String aiSummaryTopic) {
        persistMessageAndPublish(message, chatTopic, eventType, aiSummaryTopic, List.of());
    }

    /**
     * 메시지 + 멘션 행 + Outbox 이벤트를 단일 TX로 영속화.
     * 멘션 행은 발신 시점 room_members 기준으로 호출자가 미리 구성해 전달한다.
     *
     * @param mentions 멘션 엔티티 목록 (empty OK, null-safe)
     */
    @Transactional
    public void persistMessageAndPublish(ChatMessage message, String chatTopic, String eventType,
                                         String aiSummaryTopic, List<MessageMentionEntity> mentions) {
        ChatMessageEntity entity = chatMessageMapper.toEntity(message);
        if (messageEncryptor.isEnabled()) {
            entity.setContent(messageEncryptor.encrypt(message.getContent()));
        }
        chatMessageRepository.save(entity);

        if (mentions != null && !mentions.isEmpty()) {
            messageMentionRepository.saveAll(mentions);
        }

        saveOutboxEventInternal(message, chatTopic, eventType);

        if (aiSummaryTopic != null) {
            saveOutboxEventInternal(message, aiSummaryTopic, "AI_SUMMARY_REQUEST");
        }

        // 리스너가 본문을 다시 파싱하지 않도록, 방금 확정한 멘션 대상을 그대로 실어 보낸다.
        eventPublisher.publishEvent(new MessagePersistedEvent(message, mentionedUsernames(mentions)));
    }

    private static List<String> mentionedUsernames(List<MessageMentionEntity> mentions) {
        if (mentions == null || mentions.isEmpty()) return List.of();
        return mentions.stream()
                .map(MessageMentionEntity::getMentionedUsername)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
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
