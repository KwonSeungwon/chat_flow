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

import static org.mockito.Mockito.verifyNoInteractions;

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
}
