package com.chatflow.chat.service.outbox;

import com.chatflow.chat.entity.ChatMessageEntity;
import com.chatflow.chat.entity.OutboxEvent;
import com.chatflow.chat.event.MessagePersistedEvent;
import com.chatflow.chat.mapper.ChatMessageMapper;
import com.chatflow.chat.repository.ChatMessageRepository;
import com.chatflow.chat.repository.OutboxEventRepository;
import com.chatflow.common.dto.BaseMessage;
import com.chatflow.common.dto.ChatMessage;
import com.chatflow.common.dto.KafkaTopics;
import com.chatflow.common.util.MessageEncryptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ChatPersistenceService covering encryption gating
 * and outbox event writes.
 */
@ExtendWith(MockitoExtension.class)
class ChatPersistenceServiceTest {

    @Mock private ChatMessageRepository chatMessageRepository;
    @Mock private OutboxEventRepository outboxEventRepository;
    @Mock private ObjectMapper objectMapper;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private MessageEncryptor messageEncryptor;
    @Mock private ChatMessageMapper chatMessageMapper;

    private ChatPersistenceService chatPersistenceService;

    private static final String ROOM_ID = "room-1";
    private static final String MESSAGE_ID = "msg-1";
    private static final String RAW_CONTENT = "hello world";
    private static final String ENCRYPTED_CONTENT = "enc:aGVsbG8gd29ybGQ=";

    @BeforeEach
    void setUp() {
        chatPersistenceService = new ChatPersistenceService(
                chatMessageRepository, outboxEventRepository,
                objectMapper, eventPublisher, messageEncryptor,
                chatMessageMapper);
    }

    private ChatMessage sampleMessage() {
        return ChatMessage.builder()
                .messageId(MESSAGE_ID)
                .chatRoomId(ROOM_ID)
                .userId("user-1")
                .username("tester")
                .content(RAW_CONTENT)
                .timestamp(LocalDateTime.of(2026, 1, 1, 12, 0))
                .type(BaseMessage.MessageType.CHAT)
                .build();
    }

    private ChatMessageEntity sampleEntity() {
        return ChatMessageEntity.builder()
                .messageId(MESSAGE_ID)
                .chatRoomId(ROOM_ID)
                .userId("user-1")
                .username("tester")
                .content(RAW_CONTENT)
                .timestamp(LocalDateTime.of(2026, 1, 1, 12, 0))
                .type("CHAT")
                .build();
    }

    // ── Encryption ──────────────────────────────────────────────

    @Nested
    class Encryption {

        @Test
        void encrypts_content_before_save_when_encryptor_enabled() throws Exception {
            when(chatMessageMapper.toEntity(any(ChatMessage.class))).thenReturn(sampleEntity());
            when(messageEncryptor.isEnabled()).thenReturn(true);
            when(messageEncryptor.encrypt(RAW_CONTENT)).thenReturn(ENCRYPTED_CONTENT);
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");

            chatPersistenceService.persistMessageAndPublish(
                    sampleMessage(), KafkaTopics.CHAT_MESSAGES, "MESSAGE_SENT", null);

            ArgumentCaptor<ChatMessageEntity> captor = ArgumentCaptor.forClass(ChatMessageEntity.class);
            verify(chatMessageRepository).save(captor.capture());

            assertEquals(ENCRYPTED_CONTENT, captor.getValue().getContent());
            assertNotEquals(RAW_CONTENT, captor.getValue().getContent());
        }

        @Test
        void passes_content_through_when_encryptor_disabled() throws Exception {
            when(chatMessageMapper.toEntity(any(ChatMessage.class))).thenReturn(sampleEntity());
            when(messageEncryptor.isEnabled()).thenReturn(false);
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");

            chatPersistenceService.persistMessageAndPublish(
                    sampleMessage(), KafkaTopics.CHAT_MESSAGES, "MESSAGE_SENT", null);

            ArgumentCaptor<ChatMessageEntity> captor = ArgumentCaptor.forClass(ChatMessageEntity.class);
            verify(chatMessageRepository).save(captor.capture());

            assertEquals(RAW_CONTENT, captor.getValue().getContent());
        }
    }

    // ── Outbox ──────────────────────────────────────────────────

    @Nested
    class Outbox {

        @Test
        void writes_outbox_event_with_chat_messages_topic_key() throws Exception {
            when(chatMessageMapper.toEntity(any(ChatMessage.class))).thenReturn(sampleEntity());
            when(messageEncryptor.isEnabled()).thenReturn(false);
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");

            chatPersistenceService.persistMessageAndPublish(
                    sampleMessage(), KafkaTopics.CHAT_MESSAGES, "MESSAGE_SENT", null);

            ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxEventRepository).save(captor.capture());

            OutboxEvent saved = captor.getValue();
            assertEquals(KafkaTopics.CHAT_MESSAGES, saved.getTopic());
            assertEquals(ROOM_ID, saved.getPartitionKey());
            assertEquals("ChatMessage", saved.getAggregateType());
            assertEquals(MESSAGE_ID, saved.getAggregateId());
            assertEquals("MESSAGE_SENT", saved.getEventType());
        }
    }
}
