package com.chatflow.aisummary.service;

import com.chatflow.aisummary.client.ChatModelClient;
import com.chatflow.aisummary.dto.QuickReplyResponse;
import com.chatflow.common.dto.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuickReplyServiceTest {

    @Mock private ChatModelClient chatModelClient;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ListOperations<String, String> listOps;
    @Mock private ValueOperations<String, String> valueOps;

    private QuickReplyService service;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() {
        service = new QuickReplyService(chatModelClient, redisTemplate, objectMapper);
    }

    private String json(String content, String username) throws Exception {
        ChatMessage m = new ChatMessage();
        m.setMessageId("m-" + content.hashCode());
        m.setChatRoomId("room-1");
        m.setUsername(username);
        m.setUserId("u-" + username);
        m.setContent(content);
        m.setTimestamp(LocalDateTime.now());
        m.setType(ChatMessage.MessageType.CHAT);
        return objectMapper.writeValueAsString(m);
    }

    @Test
    void generateQuickReplies_returnsParsedSuggestions() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.range(eq("chatflow:summary:buffer:room-1"), eq(0L), eq(-1L)))
                .thenReturn(List.of(
                        json("회의 시작했어요", "alice"),
                        json("어디 회의실이에요?", "bob")));
        when(chatModelClient.generate(anyString()))
                .thenReturn("[\"3층 회의실이요\", \"잠시만요 확인할게요\", \"바로 갈게요\"]");

        QuickReplyResponse result = service.generateQuickReplies("room-1", "m-12345");

        assertThat(result.suggestions()).containsExactly(
                "3층 회의실이요", "잠시만요 확인할게요", "바로 갈게요");
        verify(valueOps).set(eq("chatflow:smart-reply:room-1:m-12345"),
                anyString(), eq(30L), eq(TimeUnit.MINUTES));
    }

    @Test
    void generateQuickReplies_servesFromCacheWhenPresent() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("chatflow:smart-reply:room-1:m-cached"))
                .thenReturn("[\"안녕하세요\", \"네 알겠습니다\", \"확인했어요\"]");

        QuickReplyResponse result = service.generateQuickReplies("room-1", "m-cached");

        assertThat(result.suggestions()).containsExactly(
                "안녕하세요", "네 알겠습니다", "확인했어요");
        verify(chatModelClient, never()).generate(anyString());
    }

    @Test
    void generateQuickReplies_returnsEmptyWhenBufferEmpty() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.range(anyString(), eq(0L), eq(-1L))).thenReturn(List.of());

        QuickReplyResponse result = service.generateQuickReplies("room-1", "m-xx");

        assertThat(result.suggestions()).isEmpty();
        verify(chatModelClient, never()).generate(anyString());
    }

    @Test
    void generateQuickReplies_returnsEmptyOnGeminiMalformedResponse() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.range(anyString(), eq(0L), eq(-1L)))
                .thenReturn(List.of(json("hi", "bob")));
        when(chatModelClient.generate(anyString())).thenReturn("garbage not-json");

        QuickReplyResponse result = service.generateQuickReplies("room-1", "m-x");

        assertThat(result.suggestions()).isEmpty();
        verify(valueOps, never()).set(anyString(), anyString(), any(Long.class), any(TimeUnit.class));
    }

    @Test
    void generateQuickReplies_truncatesMoreThan3Suggestions() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.range(anyString(), eq(0L), eq(-1L)))
                .thenReturn(List.of(json("hi", "bob")));
        when(chatModelClient.generate(anyString()))
                .thenReturn("[\"a\", \"b\", \"c\", \"d\", \"e\"]");

        QuickReplyResponse result = service.generateQuickReplies("room-1", "m-x");

        assertThat(result.suggestions()).containsExactly("a", "b", "c");
    }

    @Test
    void generateQuickReplies_filtersEmptyAndOverlongSuggestions() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.range(anyString(), eq(0L), eq(-1L)))
                .thenReturn(List.of(json("hi", "bob")));
        String overlong = "x".repeat(200);
        when(chatModelClient.generate(anyString()))
                .thenReturn("[\"\", \"" + overlong + "\", \"valid1\", \"valid2\"]");

        QuickReplyResponse result = service.generateQuickReplies("room-1", "m-x");

        assertThat(result.suggestions()).containsExactly("valid1", "valid2");
    }
}
