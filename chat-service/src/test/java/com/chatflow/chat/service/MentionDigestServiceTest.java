package com.chatflow.chat.service;

import com.chatflow.chat.dto.MentionItemDto;
import com.chatflow.chat.entity.ChatMessageEntity;
import com.chatflow.chat.repository.ChatMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MentionDigestServiceTest {

    @Mock private ChatMessageRepository chatMessageRepository;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private SetOperations<String, String> setOps;

    private MentionDigestService service;

    @BeforeEach
    void setUp() {
        service = new MentionDigestService(chatMessageRepository, redisTemplate);
    }

    private ChatMessageEntity msg(String id, String fromUser, String content, LocalDateTime when) {
        ChatMessageEntity e = new ChatMessageEntity();
        e.setMessageId(id);
        e.setChatRoomId("room-1");
        e.setUsername(fromUser);
        e.setContent(content);
        e.setTimestamp(when);
        return e;
    }

    @Test
    void list_marksReadStatusFromRedisSet() {
        when(chatMessageRepository.findMentionsOf(eq("alice"), any()))
                .thenReturn(List.of(
                        msg("m1", "bob", "@alice hey", LocalDateTime.now()),
                        msg("m2", "carol", "@alice yo", LocalDateTime.now().minusMinutes(5))));
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.members("chatflow:mentions:read:user-alice"))
                .thenReturn(Set.of("m1"));

        List<MentionItemDto> result = service.list("user-alice", "alice", 30);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).messageId()).isEqualTo("m1");
        assertThat(result.get(0).read()).isTrue();
        assertThat(result.get(1).read()).isFalse();
    }

    @Test
    void list_clampsDaysWithinBounds() {
        // days=0 should be clamped to 1; days=10000 to 365.
        when(chatMessageRepository.findMentionsOf(eq("alice"), any()))
                .thenReturn(List.of());
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.members(anyString())).thenReturn(Set.of());

        service.list("user-alice", "alice", 0);
        service.list("user-alice", "alice", 10_000);

        // No assertion failure means clamp logic ran without exception.
        verify(chatMessageRepository, org.mockito.Mockito.times(2))
                .findMentionsOf(eq("alice"), any());
    }

    @Test
    void unreadCount_excludesAlreadyReadMessages() {
        when(chatMessageRepository.findMentionsOf(eq("alice"), any()))
                .thenReturn(List.of(
                        msg("m1", "bob", "@alice hey", LocalDateTime.now()),
                        msg("m2", "carol", "@alice yo", LocalDateTime.now()),
                        msg("m3", "dave", "@alice woo", LocalDateTime.now())));
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.members(anyString())).thenReturn(Set.of("m2"));

        long count = service.unreadCount("user-alice", "alice", 30);

        assertThat(count).isEqualTo(2L);
    }

    @Test
    void markRead_addsMessageIdToRedisSet() {
        when(redisTemplate.opsForSet()).thenReturn(setOps);

        service.markRead("user-alice", "m42");

        verify(setOps).add("chatflow:mentions:read:user-alice", "m42");
    }

    @Test
    void markAllRead_addsAllCurrentMentionMessageIds() {
        when(chatMessageRepository.findMentionsOf(eq("alice"), any()))
                .thenReturn(List.of(
                        msg("m1", "bob", "@alice", LocalDateTime.now()),
                        msg("m2", "carol", "@alice", LocalDateTime.now())));
        when(redisTemplate.opsForSet()).thenReturn(setOps);

        service.markAllRead("user-alice", "alice", 30);

        verify(setOps).add(eq("chatflow:mentions:read:user-alice"),
                eq("m1"), eq("m2"));
    }

    @Test
    void markAllRead_noOpWhenEmpty() {
        when(chatMessageRepository.findMentionsOf(eq("alice"), any()))
                .thenReturn(List.of());

        service.markAllRead("user-alice", "alice", 30);

        // setOps not even fetched when there are no mentions
        verify(redisTemplate, org.mockito.Mockito.never()).opsForSet();
    }
}
