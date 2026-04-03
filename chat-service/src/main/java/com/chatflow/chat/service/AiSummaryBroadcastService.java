package com.chatflow.chat.service;

import com.chatflow.common.dto.ChatMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * AI 요약/답변을 Kafka에서 수신하여 해당 채팅방의 STOMP 구독자에게 실시간 전달.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiSummaryBroadcastService {

    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "ai-summaries", groupId = "chat-service-ai-broadcast")
    public void onAiSummary(String messageJson) {
        try {
            ChatMessage summary = objectMapper.readValue(messageJson, ChatMessage.class);
            String destination = "/topic/chat/" + summary.getChatRoomId();
            messagingTemplate.convertAndSend(destination, summary);
            log.info("AI summary broadcast to {}: {}", destination, summary.getMessageId());
        } catch (JsonProcessingException e) {
            log.error("AI summary 역직렬화 실패", e);
        }
    }
}
