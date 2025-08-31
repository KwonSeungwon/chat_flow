package com.chatflow.aisummary.service;

import com.chatflow.common.dto.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiSummaryService {

    private final ChatLanguageModel chatLanguageModel;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    
    private final Map<String, List<ChatMessage>> chatRoomMessages = new ConcurrentHashMap<>();
    private static final String SUMMARY_TOPIC = "ai-summaries";
    private static final int SUMMARY_TRIGGER_COUNT = 10;

    @KafkaListener(topics = "ai-summary-requests")
    public void handleSummaryRequest(ChatMessage message) {
        log.info("Received summary request for room: {}", message.getChatRoomId());
        
        String roomId = message.getChatRoomId();
        chatRoomMessages.computeIfAbsent(roomId, k -> new ArrayList<>()).add(message);
        
        List<ChatMessage> messages = chatRoomMessages.get(roomId);
        if (messages.size() >= SUMMARY_TRIGGER_COUNT) {
            generateSummary(roomId, messages);
            messages.clear(); // 요약 후 메시지 목록 초기화
        }
    }

    @KafkaListener(topics = "chat-messages")
    public void handleChatMessage(ChatMessage message) {
        log.debug("Storing message for potential summary: {}", message.getMessageId());
        
        String roomId = message.getChatRoomId();
        chatRoomMessages.computeIfAbsent(roomId, k -> new ArrayList<>()).add(message);
    }

    private void generateSummary(String roomId, List<ChatMessage> messages) {
        try {
            log.info("Generating AI summary for room: {} with {} messages", roomId, messages.size());
            
            String conversationText = buildConversationText(messages);
            String summaryPrompt = buildSummaryPrompt(conversationText);
            
            String summary = chatLanguageModel.generate(summaryPrompt);
            
            ChatMessage summaryMessage = ChatMessage.builder()
                    .chatRoomId(roomId)
                    .userId("ai-system")
                    .username("AI 요약봇")
                    .content(summary)
                    .type(ChatMessage.MessageType.AI_SUMMARY)
                    .timestamp(LocalDateTime.now())
                    .isAiGenerated(true)
                    .build();
            
            kafkaTemplate.send(SUMMARY_TOPIC, roomId, summaryMessage);
            log.info("AI summary generated and sent for room: {}", roomId);
            
        } catch (Exception e) {
            log.error("Error generating AI summary for room: {}", roomId, e);
        }
    }

    private String buildConversationText(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage message : messages) {
            if (message.getType() == ChatMessage.MessageType.CHAT) {
                sb.append(message.getUsername())
                  .append(": ")
                  .append(message.getContent())
                  .append("\n");
            }
        }
        return sb.toString();
    }

    private String buildSummaryPrompt(String conversationText) {
        return String.format("""
            다음 채팅 대화를 요약해주세요. 주요 내용과 결론을 포함하여 간결하게 요약해주세요.
            
            대화 내용:
            %s
            
            요약 규칙:
            1. 주요 토픽과 결론을 중심으로 요약
            2. 3-5문장으로 간결하게 작성
            3. 한국어로 작성
            4. 존댓말 사용
            """, conversationText);
    }
}