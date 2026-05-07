package com.chatflow.chat.service;

import com.chatflow.chat.dto.MentionItemDto;
import com.chatflow.chat.entity.ChatMessageEntity;
import com.chatflow.chat.repository.ChatMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MentionDigestService {

    private final ChatMessageRepository chatMessageRepository;
    private final StringRedisTemplate redisTemplate;

    private static final int MAX_DAYS = 365;

    private String readKey(String userId) {
        return "chatflow:mentions:read:" + userId;
    }

    private Set<String> readSet(String userId) {
        Set<String> members = redisTemplate.opsForSet().members(readKey(userId));
        return members == null ? Set.of() : members;
    }

    public List<MentionItemDto> list(String userId, String username, int days) {
        int safeDays = Math.max(1, Math.min(days, MAX_DAYS));
        LocalDateTime since = LocalDateTime.now().minusDays(safeDays);
        List<ChatMessageEntity> rows = chatMessageRepository.findMentionsOf(username, since);
        Set<String> read = readSet(userId);
        return rows.stream()
                .map(e -> MentionItemDto.from(e, read.contains(e.getMessageId())))
                .collect(Collectors.toList());
    }

    public long unreadCount(String userId, String username, int days) {
        int safeDays = Math.max(1, Math.min(days, MAX_DAYS));
        LocalDateTime since = LocalDateTime.now().minusDays(safeDays);
        List<ChatMessageEntity> rows = chatMessageRepository.findMentionsOf(username, since);
        Set<String> read = readSet(userId);
        return rows.stream().filter(e -> !read.contains(e.getMessageId())).count();
    }

    public void markRead(String userId, String messageId) {
        redisTemplate.opsForSet().add(readKey(userId), messageId);
    }

    public void markAllRead(String userId, String username, int days) {
        int safeDays = Math.max(1, Math.min(days, MAX_DAYS));
        LocalDateTime since = LocalDateTime.now().minusDays(safeDays);
        List<ChatMessageEntity> rows = chatMessageRepository.findMentionsOf(username, since);
        if (rows.isEmpty()) return;
        String[] ids = rows.stream()
                .map(ChatMessageEntity::getMessageId)
                .toArray(String[]::new);
        redisTemplate.opsForSet().add(readKey(userId), ids);
    }
}
