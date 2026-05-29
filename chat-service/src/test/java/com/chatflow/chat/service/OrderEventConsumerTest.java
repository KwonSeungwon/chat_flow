package com.chatflow.chat.service;

import com.chatflow.chat.service.outbox.ChatPersistenceService;
import com.chatflow.common.dto.ChatMessage;
import com.chatflow.common.dto.KafkaTopics;
import com.chatflow.common.dto.OrderEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for OrderEventConsumer covering order-type dispatch,
 * message construction, and malformed-payload resilience.
 */
@ExtendWith(MockitoExtension.class)
class OrderEventConsumerTest {

    @Mock private ChatPersistenceService chatPersistenceService;
    @Mock private SimpMessagingTemplate messagingTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private OrderEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new OrderEventConsumer(
                chatPersistenceService, messagingTemplate, objectMapper);
    }

    // -- helpers --------------------------------------------------------

    private static OrderEvent medicationOrder() {
        return OrderEvent.builder()
                .orderId("order-1")
                .patientId("patient-1")
                .patientName("홍길동")
                .orderType(OrderEvent.OrderType.MEDICATION)
                .description("아목시실린 500mg")
                .roomId("room-10")
                .timestamp(LocalDateTime.of(2026, 1, 1, 12, 0))
                .build();
    }

    private static OrderEvent labOrder() {
        return OrderEvent.builder()
                .orderId("order-2")
                .patientId("patient-2")
                .patientName("김철수")
                .orderType(OrderEvent.OrderType.LAB)
                .description("CBC 혈액검사")
                .roomId("room-20")
                .timestamp(LocalDateTime.of(2026, 1, 1, 14, 0))
                .build();
    }

    private String toJson(OrderEvent event) throws Exception {
        return objectMapper.writeValueAsString(event);
    }

    // -- tests ----------------------------------------------------------

    @Test
    void persists_medication_system_message_with_prescription_prefix() throws Exception {
        // given
        String json = toJson(medicationOrder());

        // when
        consumer.handleOrderEvent(json);

        // then
        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatPersistenceService).persistMessageAndPublish(
                captor.capture(),
                eq(KafkaTopics.CHAT_MESSAGES),
                eq("ORDER_EVENT"),
                isNull());

        ChatMessage msg = captor.getValue();
        assertThat(msg.getChatRoomId()).isEqualTo("room-10");
        assertThat(msg.getUserId()).isEqualTo("system");
        assertThat(msg.getUsername()).isEqualTo("시스템");
        assertThat(msg.getType()).isEqualTo(ChatMessage.MessageType.SYSTEM);
        assertThat(msg.getContent()).startsWith("[처방알림]");
        assertThat(msg.getContent()).contains("홍길동");
        assertThat(msg.getContent()).contains("아목시실린 500mg");
        assertThat(msg.getMessageId()).isNotBlank();
    }

    @Test
    void persists_lab_system_message_with_lab_prefix() throws Exception {
        // given
        String json = toJson(labOrder());

        // when
        consumer.handleOrderEvent(json);

        // then
        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatPersistenceService).persistMessageAndPublish(
                captor.capture(),
                eq(KafkaTopics.CHAT_MESSAGES),
                eq("ORDER_EVENT"),
                isNull());

        ChatMessage msg = captor.getValue();
        assertThat(msg.getChatRoomId()).isEqualTo("room-20");
        assertThat(msg.getContent()).startsWith("[검사알림]");
        assertThat(msg.getContent()).contains("김철수");
        assertThat(msg.getContent()).contains("CBC 혈액검사");
    }

    @Test
    void ignores_malformed_json_without_throwing() {
        // given: invalid JSON payload
        String badJson = "{ not valid json %%%";

        // when / then: no exception, no persistence call
        assertThatCode(() -> consumer.handleOrderEvent(badJson))
                .doesNotThrowAnyException();

        verifyNoInteractions(chatPersistenceService);
    }
}
