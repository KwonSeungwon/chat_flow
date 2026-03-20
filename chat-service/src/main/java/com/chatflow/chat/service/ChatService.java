package com.chatflow.chat.service;

import com.chatflow.common.dto.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final SimpMessagingTemplate messagingTemplate;
    private final ChatPersistenceService chatPersistenceService;

    private static final String CHAT_TOPIC = "chat-messages";
    private static final String AI_SUMMARY_TOPIC = "ai-summary-requests";

    public void processMessage(ChatMessage message) {
        message.setMessageId(UUID.randomUUID().toString());
        message.setTimestamp(LocalDateTime.now());

        log.info("Processing chat message: {}", message.getMessageId());

        // WebSocket으로 실시간 브로드캐스트 (트랜잭션 밖에서 즉시 전달)
        messagingTemplate.convertAndSend("/topic/chat/" + message.getChatRoomId(), message);

        // DB 저장 + Outbox 이벤트 생성 (하나의 트랜잭션)
        chatPersistenceService.persistWithOutbox(message, CHAT_TOPIC, "MESSAGE_SENT");

        // AI 요약 요청 (특정 조건 만족시)
        if (shouldRequestAISummary(message)) {
            chatPersistenceService.saveOutboxEvent(message, AI_SUMMARY_TOPIC, "AI_SUMMARY_REQUEST");
        }
    }

    public void addUser(ChatMessage message) {
        message.setType(ChatMessage.MessageType.JOIN);
        message.setTimestamp(LocalDateTime.now());
        message.setMessageId(UUID.randomUUID().toString());
        message.setContent(message.getUsername() + "님이 입장하셨습니다.");

        log.info("User {} joined chat room {}", message.getUsername(), message.getChatRoomId());

        messagingTemplate.convertAndSend("/topic/chat/" + message.getChatRoomId(), message);
        chatPersistenceService.saveOutboxEvent(message, CHAT_TOPIC, "USER_JOINED");
    }

    private boolean shouldRequestAISummary(ChatMessage message) {
        return message.getContent() != null && message.getContent().length() > 100;
    }
}
