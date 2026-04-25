package com.chatflow.chat.service;

import com.chatflow.chat.config.RedisHealthTracker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 방 단위 Redis 캐시 무효화. 참가자 수, 방 상태 변경 시 호출.
 * 여러 서비스(ChatRoomService, ParticipantService, DmRoomService, MessagePinService)에서 공유.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RoomCacheEvictor {

    private static final String ROOM_CACHE_KEY = "chatflow:room:";
    private static final String ROOMS_LIST_KEY = "chatflow:rooms:list";

    private final StringRedisTemplate redisTemplate;
    private final RedisHealthTracker redisHealth;

    public void evict(String roomId) {
        if (redisHealth.isCircuitOpen()) return;
        try {
            redisTemplate.delete(ROOM_CACHE_KEY + roomId);
            redisTemplate.delete(ROOMS_LIST_KEY);
            redisHealth.recordSuccess();
        } catch (Exception e) {
            redisHealth.recordFailure(e);
            log.warn("Room cache evict failed: roomId={}, err={}", roomId, e.getMessage());
        }
    }
}
