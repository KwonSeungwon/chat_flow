package com.chatflow.chat.service;

import com.chatflow.chat.config.RedisHealthTracker;
import com.chatflow.chat.repository.ChatMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for UnreadCountService covering Redis cache-hit / cache-miss
 * paths and the batched multi-room lookup.
 */
@ExtendWith(MockitoExtension.class)
class UnreadCountServiceTest {

    @Mock private ChatMessageRepository chatMessageRepository;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private RedisHealthTracker redisHealth;
    @Mock private ValueOperations<String, String> valueOperations;

    private UnreadCountService unreadCountService;

    private static final String USER_ID = "user-1";
    private static final String ROOM_A = "room-a";
    private static final String ROOM_B = "room-b";
    private static final String ROOM_C = "room-c";

    @BeforeEach
    void setUp() {
        unreadCountService = new UnreadCountService(
                chatMessageRepository, redisTemplate, redisHealth);
    }

    // ── GetUnreadCounts ─────────────────────────────────────────────

    @Nested
    class GetUnreadCounts {

        @Test
        void returns_zero_when_lastReadMessageId_blank() {
            // readAt null in Redis for a single room → epoch cutoff → batch query returns nothing
            // (room has 0 messages) → result is 0
            when(redisHealth.isCircuitOpen()).thenReturn(false);
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            List<String> redisValues = new java.util.ArrayList<>();
            redisValues.add(null);
            when(valueOperations.multiGet(anyList())).thenReturn(redisValues);

            // Batch query returns empty list → no rows → count stays at initial 0
            when(chatMessageRepository.countNewChatMessagesBatch(anyList(), any(LocalDateTime.class)))
                    .thenReturn(Collections.emptyList());

            Map<String, Long> result = unreadCountService.getUnreadCounts(USER_ID, List.of(ROOM_A));

            assertEquals(1, result.size());
            assertEquals(0L, result.get(ROOM_A));
        }

        @Test
        void counts_messages_after_lastRead_via_repo_when_cache_miss() {
            // Redis returns null for readAt → cache miss → repo batch query
            when(redisHealth.isCircuitOpen()).thenReturn(false);
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            // null value in list = cache miss for that room
            List<String> redisValues = new java.util.ArrayList<>();
            redisValues.add(null);
            when(valueOperations.multiGet(anyList())).thenReturn(redisValues);

            // Batch query returns count of 7 for ROOM_A
            List<Object[]> batchResult = Collections.singletonList(new Object[]{ROOM_A, 7L});
            when(chatMessageRepository.countNewChatMessagesBatch(anyList(), any(LocalDateTime.class)))
                    .thenReturn(batchResult);

            Map<String, Long> result = unreadCountService.getUnreadCounts(USER_ID, List.of(ROOM_A));

            assertEquals(7L, result.get(ROOM_A));
            verify(chatMessageRepository).countNewChatMessagesBatch(anyList(), any(LocalDateTime.class));
        }

        @Test
        void returns_redis_cached_value_when_present() {
            // Redis returns a valid readAt timestamp → cache hit → individual repo query
            String readAt = LocalDateTime.of(2026, 5, 1, 10, 0).toString();

            when(redisHealth.isCircuitOpen()).thenReturn(false);
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.multiGet(anyList())).thenReturn(List.of(readAt));

            when(chatMessageRepository.countNewChatMessages(eq(ROOM_A), any(LocalDateTime.class)))
                    .thenReturn(42L);

            Map<String, Long> result = unreadCountService.getUnreadCounts(USER_ID, List.of(ROOM_A));

            assertEquals(42L, result.get(ROOM_A));
            // Cache hit path uses individual query, NOT batch query
            verify(chatMessageRepository).countNewChatMessages(eq(ROOM_A), any(LocalDateTime.class));
            verify(chatMessageRepository, never()).countNewChatMessagesBatch(anyList(), any());
        }

        @Test
        void batched_lookup_for_multiple_rooms_returns_map() {
            // 3 rooms: ROOM_A has readAt, ROOM_B has readAt, ROOM_C has none (no read marker)
            String readAtA = LocalDateTime.of(2026, 5, 1, 10, 0).toString();
            String readAtB = LocalDateTime.of(2026, 5, 2, 14, 30).toString();
            List<String> redisValues = new java.util.ArrayList<>();
            redisValues.add(readAtA);
            redisValues.add(readAtB);
            redisValues.add(null);  // ROOM_C has no read marker

            when(redisHealth.isCircuitOpen()).thenReturn(false);
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.multiGet(anyList())).thenReturn(redisValues);

            // ROOM_C goes through batch query (no readAt → epoch cutoff)
            List<Object[]> batchResult = Collections.singletonList(new Object[]{ROOM_C, 15L});
            when(chatMessageRepository.countNewChatMessagesBatch(anyList(), any(LocalDateTime.class)))
                    .thenReturn(batchResult);

            // ROOM_A and ROOM_B go through individual queries (readAt present)
            when(chatMessageRepository.countNewChatMessages(eq(ROOM_A), any(LocalDateTime.class)))
                    .thenReturn(3L);
            when(chatMessageRepository.countNewChatMessages(eq(ROOM_B), any(LocalDateTime.class)))
                    .thenReturn(0L);

            Map<String, Long> result = unreadCountService.getUnreadCounts(
                    USER_ID, List.of(ROOM_A, ROOM_B, ROOM_C));

            // All 3 entries present
            assertEquals(3, result.size());
            assertEquals(3L, result.get(ROOM_A));
            assertEquals(0L, result.get(ROOM_B));
            assertEquals(15L, result.get(ROOM_C));
        }
    }
}
