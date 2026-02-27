package com.chatflow.chat.service;

import com.chatflow.chat.entity.ChatMessageEntity;
import com.chatflow.chat.repository.ChatMessageRepository;
import com.chatflow.common.dto.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final SimpMessagingTemplate messagingTemplate;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ChatMessageRepository chatMessageRepository;

    private static final String CHAT_TOPIC = "chat-messages";
    private static final String AI_SUMMARY_TOPIC = "ai-summary-requests";

    public void processMessage(ChatMessage message) {
        message.setMessageId(UUID.randomUUID().toString());
        message.setTimestamp(LocalDateTime.now());

        log.info("Processing chat message: {}", message.getMessageId());

        // DB에 메시지 영속화
        persistMessage(message);

        // WebSocket으로 실시간 브로드캐스트
        messagingTemplate.convertAndSend("/topic/chat/" + message.getChatRoomId(), message);

        // Kafka로 메시지 전송 (AI 요약 및 검색 인덱싱용)
        sendToKafka(CHAT_TOPIC, message.getChatRoomId(), message);

        // AI 요약 요청 (특정 조건 만족시)
        if (shouldRequestAISummary(message)) {
            sendToKafka(AI_SUMMARY_TOPIC, message.getChatRoomId(), message);
        }
    }

    public void addUser(ChatMessage message) {
        message.setType(ChatMessage.MessageType.JOIN);
        message.setTimestamp(LocalDateTime.now());
        message.setContent(message.getUsername() + "님이 입장하셨습니다.");

        log.info("User {} joined chat room {}", message.getUsername(), message.getChatRoomId());

        messagingTemplate.convertAndSend("/topic/chat/" + message.getChatRoomId(), message);
        sendToKafka(CHAT_TOPIC, message.getChatRoomId(), message);
    }

    private void persistMessage(ChatMessage message) {
        try {
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
        } catch (Exception e) {
            log.error("Failed to persist message: {}", message.getMessageId(), e);
        }
    }

    private void sendToKafka(String topic, String key, Object message) {
        kafkaTemplate.send(topic, key, message)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to send message to Kafka topic '{}': {}", topic, ex.getMessage());
                    } else {
                        log.debug("Message sent to Kafka topic '{}', partition: {}, offset: {}",
                                topic, result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
                    }
                });
    }

    private boolean shouldRequestAISummary(ChatMessage message) {
        return message.getContent() != null && message.getContent().length() > 100;
    }
}
