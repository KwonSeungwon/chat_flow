package com.chatflow.chat.service;

import com.chatflow.chat.config.RedisHealthTracker;
import com.chatflow.chat.entity.ChatRoom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoomVisibilityServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private RedisHealthTracker redisHealth;
    @Mock private HashOperations<String, Object, Object> hashOps;

    @InjectMocks
    private RoomVisibilityService roomVisibilityService;

    private static final String HIDDEN_KEY_PREFIX = "chatflow:hidden:";

    // ── hide ─────────────────────────────────────────────────────

    @Test
    void hide_storesTimestampInRedisHash() {
        // given
        when(redisHealth.isCircuitOpen()).thenReturn(false);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);

        // when
        roomVisibilityService.hide("user-1", "room-42");

        // then
        verify(hashOps).put(
                eq(HIDDEN_KEY_PREFIX + "user-1"),
                eq("room-42"),
                argThat(val -> {
                    // Value should be an ISO-8601 Instant string
                    try {
                        Instant.parse((String) val);
                        return true;
                    } catch (Exception e) {
                        return false;
                    }
                }));
        verify(redisHealth).recordSuccess();
    }

    // ── getHiddenMap ─────────────────────────────────────────────

    @Test
    void getHiddenMap_redisHealthCircuitOpen_returnsEmpty() {
        // given
        when(redisHealth.isCircuitOpen()).thenReturn(true);

        // when
        Map<String, Instant> result = roomVisibilityService.getHiddenMap("user-1");

        // then
        assertTrue(result.isEmpty());
        verify(redisTemplate, never()).opsForHash();
    }

    @Test
    void getHiddenMap_redisFailure_returnsEmptyAndRecordsFailure() {
        // given
        when(redisHealth.isCircuitOpen()).thenReturn(false);
        RuntimeException redisError = new RuntimeException("Connection refused");
        when(redisTemplate.opsForHash()).thenThrow(redisError);

        // when
        Map<String, Instant> result = roomVisibilityService.getHiddenMap("user-1");

        // then
        assertTrue(result.isEmpty());
        verify(redisHealth).recordFailure(redisError);
    }

    @Test
    void getHiddenMap_returnsParsedInstants() {
        // given
        when(redisHealth.isCircuitOpen()).thenReturn(false);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);

        Instant now = Instant.parse("2026-04-23T10:00:00Z");
        Map<Object, Object> rawEntries = new HashMap<>();
        rawEntries.put("room-1", now.toString());
        rawEntries.put("room-2", now.plusSeconds(60).toString());
        when(hashOps.entries(HIDDEN_KEY_PREFIX + "user-1")).thenReturn(rawEntries);

        // when
        Map<String, Instant> result = roomVisibilityService.getHiddenMap("user-1");

        // then
        assertEquals(2, result.size());
        assertEquals(now, result.get("room-1"));
        assertEquals(now.plusSeconds(60), result.get("room-2"));
        verify(redisHealth).recordSuccess();
    }

    // ── isVisible ────────────────────────────────────────────────

    @Test
    void isVisible_notHidden_true() {
        // given
        ChatRoom room = ChatRoom.builder().id("room-99").build();
        Map<String, Instant> hiddenMap = Map.of(); // room-99 not in map

        // when & then
        assertTrue(roomVisibilityService.isVisible(room, hiddenMap));
    }

    @Test
    void isVisible_hiddenAndNoNewMessage_false() {
        // given: hidden_at is in the past, lastMessageAt < hidden_at
        Instant hiddenAt = Instant.parse("2026-04-23T12:00:00Z");
        LocalDateTime lastMsg = LocalDateTime.ofInstant(
                hiddenAt.minusSeconds(3600), ZoneId.systemDefault());
        ChatRoom room = ChatRoom.builder().id("room-1").lastMessageAt(lastMsg).build();
        Map<String, Instant> hiddenMap = Map.of("room-1", hiddenAt);

        // when & then
        assertFalse(roomVisibilityService.isVisible(room, hiddenMap));
    }

    @Test
    void isVisible_hiddenAndNewMessage_true() {
        // given: lastMessageAt > hidden_at -> auto-unhide
        Instant hiddenAt = Instant.parse("2026-04-23T12:00:00Z");
        LocalDateTime lastMsg = LocalDateTime.ofInstant(
                hiddenAt.plusSeconds(60), ZoneId.systemDefault());
        ChatRoom room = ChatRoom.builder().id("room-1").lastMessageAt(lastMsg).build();
        Map<String, Instant> hiddenMap = Map.of("room-1", hiddenAt);

        // when & then
        assertTrue(roomVisibilityService.isVisible(room, hiddenMap));
    }

    @Test
    void isVisible_hiddenAndLastMessageNull_false() {
        // given: no messages in room -> stays hidden
        ChatRoom room = ChatRoom.builder().id("room-1").lastMessageAt(null).build();
        Instant hiddenAt = Instant.parse("2026-04-23T12:00:00Z");
        Map<String, Instant> hiddenMap = Map.of("room-1", hiddenAt);

        // when & then
        assertFalse(roomVisibilityService.isVisible(room, hiddenMap));
    }

    @Test
    void isVisible_invalidTimestampInRedis_treatedAsVisible() {
        // given: getHiddenMap returns a map where the Instant was already parsed,
        // but simulating "invalid data" means the entry simply wouldn't be in the map
        // (getHiddenMap skips unparseable entries).
        // So a room not in the hiddenMap is visible.
        when(redisHealth.isCircuitOpen()).thenReturn(false);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);

        Map<Object, Object> rawEntries = new HashMap<>();
        rawEntries.put("room-1", "not-a-valid-timestamp");
        when(hashOps.entries(HIDDEN_KEY_PREFIX + "user-1")).thenReturn(rawEntries);

        // when: getHiddenMap silently drops invalid entries
        Map<String, Instant> hiddenMap = roomVisibilityService.getHiddenMap("user-1");

        // then: room-1 is NOT in hiddenMap (parse failure -> ignored)
        assertFalse(hiddenMap.containsKey("room-1"));

        // and isVisible treats it as visible
        ChatRoom room = ChatRoom.builder().id("room-1").build();
        assertTrue(roomVisibilityService.isVisible(room, hiddenMap));
    }
}
