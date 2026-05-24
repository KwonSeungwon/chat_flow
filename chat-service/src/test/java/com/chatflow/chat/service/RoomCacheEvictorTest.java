package com.chatflow.chat.service;

import com.chatflow.chat.config.RedisHealthTracker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoomCacheEvictorTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private RedisHealthTracker redisHealth;

    @InjectMocks
    private RoomCacheEvictor roomCacheEvictor;

    private static final String ROOM_CACHE_KEY = "chatflow:room:";
    private static final String ROOMS_LIST_KEY = "chatflow:rooms:list";

    // ── evict ────────────────────────────────────────────────────

    @Test
    void evict_deletes_room_cache_and_rooms_list_keys() {
        // given
        String roomId = "room-42";
        when(redisHealth.isCircuitOpen()).thenReturn(false);

        // when
        roomCacheEvictor.evict(roomId);

        // then
        verify(redisTemplate).delete(ROOM_CACHE_KEY + roomId);
        verify(redisTemplate).delete(ROOMS_LIST_KEY);
        verify(redisHealth).recordSuccess();
    }

    @Test
    void evict_noop_when_circuit_open() {
        // given
        when(redisHealth.isCircuitOpen()).thenReturn(true);

        // when
        roomCacheEvictor.evict("room-99");

        // then
        verify(redisTemplate, never()).delete(anyString());
        verify(redisHealth, never()).recordSuccess();
        verify(redisHealth, never()).recordFailure(any());
    }

    @Test
    void evict_records_redis_failure_on_exception() {
        // given
        String roomId = "room-7";
        when(redisHealth.isCircuitOpen()).thenReturn(false);
        RuntimeException redisError = new RuntimeException("Connection refused");
        when(redisTemplate.delete(ROOM_CACHE_KEY + roomId)).thenThrow(redisError);

        // when
        roomCacheEvictor.evict(roomId);

        // then
        verify(redisHealth).recordFailure(redisError);
        verify(redisHealth, never()).recordSuccess();
    }
}
