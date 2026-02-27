package com.chatflow.aisummary.service;

import com.chatflow.aisummary.client.ChatModelClient;
import com.chatflow.common.dto.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiSummaryService {

    private final ChatModelClient chatModelClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    private final Map<String, List<ChatMessage>> chatRoomMessages = new ConcurrentHashMap<>();
    private final Map<String, List<ChatMessage>> roomSummaries = new ConcurrentHashMap<>();

    private static final String SUMMARY_TOPIC = "ai-summaries";
    private static final int SUMMARY_TRIGGER_COUNT = 10;
    private static final int MAX_MESSAGES_PER_ROOM = 100;

    @KafkaListener(topics = "ai-summary-requests")
    public void handleSummaryRequest(ChatMessage message) {
        log.info("Received summary request for room: {}", message.getChatRoomId());
        addMessageAndCheckTrigger(message);
    }

    @KafkaListener(topics = "chat-messages")
    public void handleChatMessage(ChatMessage message) {
        log.debug("Storing message for potential summary: {}", message.getMessageId());
        addMessageAndCheckTrigger(message);
    }

    private synchronized void addMessageAndCheckTrigger(ChatMessage message) {
        String roomId = message.getChatRoomId();
        List<ChatMessage> messages = chatRoomMessages.computeIfAbsent(
                roomId, k -> Collections.synchronizedList(new ArrayList<>()));

        messages.add(message);

        // 최대 크기 초과 시 오래된 메시지 제거
        while (messages.size() > MAX_MESSAGES_PER_ROOM) {
            messages.remove(0);
        }

        if (messages.size() >= SUMMARY_TRIGGER_COUNT) {
            List<ChatMessage> snapshot = new ArrayList<>(messages);
            messages.clear();
            generateSummary(roomId, snapshot);
        }
    }

    private void generateSummary(String roomId, List<ChatMessage> messages) {
        try {
            log.info("Generating AI summary for room: {} with {} messages", roomId, messages.size());

            String conversationText = buildConversationText(messages);
            String summaryPrompt = buildSummaryPrompt(conversationText);

            String summary = chatModelClient.generate(summaryPrompt);

            ChatMessage summaryMessage = ChatMessage.builder()
                    .messageId(java.util.UUID.randomUUID().toString())
                    .chatRoomId(roomId)
                    .userId("ai-system")
                    .username("AI 요약봇")
                    .content(summary)
                    .type(ChatMessage.MessageType.AI_SUMMARY)
                    .timestamp(LocalDateTime.now())
                    .isAiGenerated(true)
                    .build();

            // 요약 저장 (조회용)
            roomSummaries.computeIfAbsent(roomId, k -> Collections.synchronizedList(new ArrayList<>()))
                    .add(summaryMessage);

            kafkaTemplate.send(SUMMARY_TOPIC, roomId, summaryMessage)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Failed to send summary to Kafka for room: {}", roomId, ex);
                        }
                    });

            log.info("AI summary generated and sent for room: {}", roomId);

        } catch (Exception e) {
            log.error("Error generating AI summary for room: {}", roomId, e);
        }
    }

    /**
     * 특정 채팅방의 요약 목록을 반환한다.
     */
    public List<ChatMessage> getSummaries(String roomId) {
        return roomSummaries.getOrDefault(roomId, List.of());
    }

    /**
     * 특정 채팅방에 대해 즉시 요약을 생성한다.
     */
    public void requestSummary(String roomId) {
        List<ChatMessage> messages = chatRoomMessages.get(roomId);
        if (messages != null && !messages.isEmpty()) {
            List<ChatMessage> snapshot;
            synchronized (messages) {
                snapshot = new ArrayList<>(messages);
                messages.clear();
            }
            generateSummary(roomId, snapshot);
        } else {
            log.info("No messages to summarize for room: {}", roomId);
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
