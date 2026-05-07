package com.chatflow.aisummary.service;

import com.chatflow.aisummary.client.ChatModelClient;
import com.chatflow.aisummary.dto.QuickReplyResponse;
import com.chatflow.common.dto.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
public class QuickReplyService {

    private final ChatModelClient chatModelClient;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String BUFFER_KEY_PREFIX = "chatflow:summary:buffer:";
    private static final String CACHE_KEY_PREFIX = "chatflow:smart-reply:";
    private static final long CACHE_TTL_MINUTES = 30L;
    private static final int MAX_CONTEXT_MESSAGES = 10;
    private static final int MAX_SUGGESTION_LENGTH = 60;
    private static final int TARGET_SUGGESTION_COUNT = 3;

    @Autowired
    public QuickReplyService(
            ChatModelClient chatModelClient,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper) {
        this.chatModelClient = chatModelClient;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public QuickReplyResponse generateQuickReplies(String roomId, String latestMessageId) {
        // 1. Cache lookup
        String cacheKey = CACHE_KEY_PREFIX + roomId + ":" + latestMessageId;
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            try {
                List<String> suggestions = objectMapper.readValue(
                        cached, new TypeReference<List<String>>() {});
                return new QuickReplyResponse(suggestions);
            } catch (Exception e) {
                log.warn("Smart-reply cache poisoned at {} — refetching", cacheKey);
            }
        }

        // 2. Read recent messages from the existing summary buffer (peek, no pop)
        String bufferKey = BUFFER_KEY_PREFIX + roomId;
        List<String> raw = redisTemplate.opsForList().range(bufferKey, 0, -1);
        if (raw == null || raw.isEmpty()) {
            return new QuickReplyResponse(List.of());
        }

        List<ChatMessage> messages = new ArrayList<>();
        for (String json : raw) {
            try {
                messages.add(objectMapper.readValue(json, ChatMessage.class));
            } catch (Exception e) {
                // Skip malformed entries
            }
        }
        if (messages.isEmpty()) {
            return new QuickReplyResponse(List.of());
        }

        List<ChatMessage> context = messages.size() > MAX_CONTEXT_MESSAGES
                ? messages.subList(messages.size() - MAX_CONTEXT_MESSAGES, messages.size())
                : messages;

        // 3. Build prompt
        String prompt = buildPrompt(context);

        // 4. Call Gemini
        String rawResponse;
        try {
            rawResponse = chatModelClient.generate(prompt);
        } catch (Exception e) {
            log.warn("Gemini call failed for quick-reply on room {}: {}", roomId, e.getMessage());
            return new QuickReplyResponse(List.of());
        }

        // 5. Parse + filter
        List<String> suggestions = parseSuggestions(rawResponse);
        if (suggestions.isEmpty()) {
            return new QuickReplyResponse(List.of());
        }

        // 6. Cache (only valid responses)
        try {
            redisTemplate.opsForValue().set(cacheKey,
                    objectMapper.writeValueAsString(suggestions),
                    CACHE_TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.debug("Failed to cache quick-reply for {}: {}", cacheKey, e.getMessage());
        }

        return new QuickReplyResponse(suggestions);
    }

    private String buildPrompt(List<ChatMessage> context) {
        StringBuilder sb = new StringBuilder();
        sb.append("다음은 채팅방의 최근 대화입니다:\n");
        for (ChatMessage m : context) {
            sb.append(m.getUsername()).append(": ").append(m.getContent()).append('\n');
        }
        sb.append("\n마지막 메시지에 대한 자연스러운 짧은 답장 후보 3개를 생성하세요.\n");
        sb.append("각 답장은 30자 이내, 한국어로, JSON 배열로만 응답:\n");
        sb.append("[\"답장1\", \"답장2\", \"답장3\"]\n");
        return sb.toString();
    }

    private List<String> parseSuggestions(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) return List.of();
        String trimmed = rawResponse.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) trimmed = trimmed.substring(firstNewline + 1);
            if (trimmed.endsWith("```")) trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
        }
        try {
            List<String> raw = objectMapper.readValue(trimmed, new TypeReference<List<String>>() {});
            return raw.stream()
                    .filter(s -> s != null && !s.isBlank() && s.length() <= MAX_SUGGESTION_LENGTH)
                    .limit(TARGET_SUGGESTION_COUNT)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.debug("Smart-reply parse failed: {}", e.getMessage());
            return List.of();
        }
    }
}
