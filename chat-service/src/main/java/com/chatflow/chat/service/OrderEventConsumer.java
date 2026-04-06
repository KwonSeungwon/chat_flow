package com.chatflow.chat.service;

import com.chatflow.common.dto.ChatMessage;
import com.chatflow.common.dto.OrderEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderEventConsumer {

    private static final String CHAT_TOPIC = "chat-messages";

    private final ChatPersistenceService chatPersistenceService;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "order-events")
    public void handleOrderEvent(String messageJson) {
        OrderEvent event;
        try {
            event = objectMapper.readValue(messageJson, OrderEvent.class);
        } catch (JsonProcessingException e) {
            log.error("OrderEvent 역직렬화 실패", e);
            return;
        }

        String prefix = event.getOrderType() == OrderEvent.OrderType.MEDICATION ? "[처방알림]" : "[검사알림]";
        String content = prefix + " " + event.getPatientName() + " - " + event.getDescription();

        ChatMessage systemMessage = ChatMessage.builder()
                .messageId(UUID.randomUUID().toString())
                .chatRoomId(event.getRoomId())
                .userId("system")
                .username("시스템")
                .content(content)
                .type(ChatMessage.MessageType.SYSTEM)
                .timestamp(LocalDateTime.now())
                .build();

        chatPersistenceService.persistMessageAndPublish(systemMessage, CHAT_TOPIC, "ORDER_EVENT", null);
        log.info("OrderEvent 처리 완료: roomId={}, orderId={}", event.getRoomId(), event.getOrderId());
    }
}
