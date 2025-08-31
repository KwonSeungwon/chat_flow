package com.chatflow.chat.service;

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

    private static final String CHAT_TOPIC = "chat-messages";
    private static final String AI_SUMMARY_TOPIC = "ai-summary-requests";

    public void processMessage(ChatMessage message) {
        message.setMessageId(UUID.randomUUID().toString());
        message.setTimestamp(LocalDateTime.now());
        
        log.info("Processing chat message: {}", message.getMessageId());
        
        // WebSocket으로 실시간 브로드캐스트
        messagingTemplate.convertAndSend("/topic/chat/" + message.getChatRoomId(), message);
        
        // Kafka로 메시지 전송 (AI 요약 및 검색 인덱싱용)
        kafkaTemplate.send(CHAT_TOPIC, message.getChatRoomId(), message);
        
        // AI 요약 요청 (특정 조건 만족시)
        if (shouldRequestAISummary(message)) {
            kafkaTemplate.send(AI_SUMMARY_TOPIC, message.getChatRoomId(), message);
        }
    }

    public void addUser(ChatMessage message) {
        message.setType(ChatMessage.MessageType.JOIN);
        message.setTimestamp(LocalDateTime.now());
        message.setContent(message.getUsername() + "님이 입장하셨습니다.");
        
        log.info("User {} joined chat room {}", message.getUsername(), message.getChatRoomId());
        
        messagingTemplate.convertAndSend("/topic/chat/" + message.getChatRoomId(), message);
        kafkaTemplate.send(CHAT_TOPIC, message.getChatRoomId(), message);
    }

    private boolean shouldRequestAISummary(ChatMessage message) {
        // TODO: 구체적인 AI 요약 요청 조건 구현
        // 예: 메시지 수가 일정 개수에 도달했을 때, 시간 간격 등
        return message.getContent().length() > 100;
    }
}