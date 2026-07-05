package com.chatflow.aisummary.service;

import com.chatflow.aisummary.client.ChatModelClient;
import com.chatflow.common.dto.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.Executor;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiSummaryServiceTest {

    @Mock private ChatModelClient chatModelClient;
    @Mock private KafkaTemplate<String, Object> kafkaTemplate;
    @Mock private StringRedisTemplate redisTemplate;

    // Inline executor runs tasks synchronously in tests
    private final Executor syncExecutor = Runnable::run;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private AiSummaryService service;

    @BeforeEach
    void setUp() {
        service = new AiSummaryService(chatModelClient, kafkaTemplate, redisTemplate,
                objectMapper, syncExecutor);
    }

    @Test
    void addMessage_skips_non_chat_types() throws Exception {
        // The filter at the top of addMessageAndCheckTrigger must return
        // before any Redis interaction. Asserting "no interactions on
        // redisTemplate" is the strongest contract — covers opsForList,
        // opsForSet, opsForValue, expire, delete, all of them.
        ChatMessage join = ChatMessage.builder()
                .chatRoomId("room-filter").userId("u1").username("alice")
                .content("alice joined").type(ChatMessage.MessageType.JOIN)
                .timestamp(LocalDateTime.now())
                .build();

        service.handleChatMessage(objectMapper.writeValueAsString(join));

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void addMessage_skips_deleted_messages() throws Exception {
        // A MESSAGE_DELETED outbox event from chat-service carries type=CHAT
        // and isDeleted=true. The summary buffer must NOT ingest it — the
        // placeholder "삭제된 메시지입니다." would pollute AI summary prompts.
        ChatMessage deleted = ChatMessage.builder()
                .chatRoomId("room-filter").userId("u1").username("alice")
                .content("삭제된 메시지입니다.").type(ChatMessage.MessageType.CHAT)
                .timestamp(LocalDateTime.now())
                .isDeleted(true)
                .build();

        service.handleChatMessage(objectMapper.writeValueAsString(deleted));

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void addMessage_skips_edited_messages() throws Exception {
        // A MESSAGE_EDITED outbox event from chat-service carries type=CHAT
        // and edited=true. The summary buffer must NOT ingest it — the same
        // messageId with updated content would be appended a second time,
        // inflating the 10-message trigger and duplicating content in the summary.
        ChatMessage edited = ChatMessage.builder()
                .chatRoomId("room-filter").userId("u1").username("alice")
                .content("edited content").type(ChatMessage.MessageType.CHAT)
                .timestamp(LocalDateTime.now())
                .edited(true)
                .build();

        service.handleChatMessage(objectMapper.writeValueAsString(edited));

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void addMessage_buffers_normal_chat_messages() throws Exception {
        // A normal CHAT message (not deleted, not edited) MUST reach the
        // Redis buffer — verifying at least opsForList() is called.
        ChatMessage normal = ChatMessage.builder()
                .chatRoomId("room-normal").userId("u1").username("alice")
                .content("hello everyone").type(ChatMessage.MessageType.CHAT)
                .timestamp(LocalDateTime.now())
                .build();

        var listOps = mock(org.springframework.data.redis.core.ListOperations.class);
        var setOps = mock(org.springframework.data.redis.core.SetOperations.class);
        var valueOps = mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(listOps.rightPush(anyString(), anyString())).thenReturn(1L);
        when(listOps.size(anyString())).thenReturn(1L);

        service.handleChatMessage(objectMapper.writeValueAsString(normal));

        verify(listOps).rightPush(eq("chatflow:buffer:room-normal"), anyString());
    }
}
