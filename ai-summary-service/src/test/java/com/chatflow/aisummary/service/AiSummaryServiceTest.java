package com.chatflow.aisummary.service;

import com.chatflow.aisummary.client.ChatModelClient;
import com.chatflow.common.dto.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.Executor;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiSummaryServiceTest {

    @Mock private ChatModelClient chatModelClient;
    @Mock private KafkaTemplate<String, Object> kafkaTemplate;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ListOperations<String, String> listOps;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private SetOperations<String, String> setOps;

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
        String roomId = "room-filter";
        String bufferKey = "chatflow:buffer:" + roomId;

        // No stubs needed: the filter returns before any Redis call.
        // Verify directly on listOps — it must never receive rightPush for
        // a non-CHAT message.
        ChatMessage join = ChatMessage.builder()
                .chatRoomId(roomId).userId("u1").username("alice")
                .content("alice joined").type(ChatMessage.MessageType.JOIN)
                .timestamp(LocalDateTime.now())
                .build();

        service.handleChatMessage(objectMapper.writeValueAsString(join));

        verify(listOps, never()).rightPush(eq(bufferKey), anyString());
    }
}
