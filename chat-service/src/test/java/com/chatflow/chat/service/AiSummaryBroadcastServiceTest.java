package com.chatflow.chat.service;

import com.chatflow.common.dto.BaseMessage;
import com.chatflow.common.dto.ChatMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiSummaryBroadcastServiceTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private ObjectMapper objectMapper;
    private AiSummaryBroadcastService aiSummaryBroadcastService;

    private static final String ROOM_ID = "room-1";
    private static final String MESSAGE_ID = "ai-msg-1";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        aiSummaryBroadcastService = new AiSummaryBroadcastService(
                messagingTemplate, objectMapper);
    }

    @Test
    void broadcasts_AI_SUMMARY_on_topic_when_consumed() throws JsonProcessingException {
        ChatMessage summary = ChatMessage.builder()
                .messageId(MESSAGE_ID)
                .chatRoomId(ROOM_ID)
                .userId("ai-bot")
                .username("AI Assistant")
                .content("Today the team discussed deployment strategies.")
                .timestamp(LocalDateTime.of(2026, 5, 24, 10, 0))
                .type(BaseMessage.MessageType.AI_SUMMARY)
                .build();

        String json = objectMapper.writeValueAsString(summary);

        aiSummaryBroadcastService.onAiSummary(json);

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messagingTemplate).convertAndSend(
                eq("/topic/chat/" + ROOM_ID), captor.capture());

        ChatMessage broadcast = captor.getValue();
        assertEquals(BaseMessage.MessageType.AI_SUMMARY, broadcast.getType());
        assertEquals(ROOM_ID, broadcast.getChatRoomId());
        assertEquals(MESSAGE_ID, broadcast.getMessageId());
        assertEquals("Today the team discussed deployment strategies.", broadcast.getContent());
    }

    @Test
    void ignores_malformed_payload_without_throwing() {
        assertDoesNotThrow(() ->
                aiSummaryBroadcastService.onAiSummary("{invalid json}"));

        verifyNoInteractions(messagingTemplate);
    }
}
