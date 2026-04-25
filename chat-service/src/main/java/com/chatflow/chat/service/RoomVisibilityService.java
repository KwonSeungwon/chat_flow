package com.chatflow.chat.service;

import com.chatflow.chat.config.RedisHealthTracker;
import com.chatflow.chat.entity.ChatRoom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

/**
 * DM 방 per-user soft-hide 관리.
 * Redis HASH: chatflow:hidden:{userId} → { roomId: hidden_at_ISO8601 }
 * 새 메시지가 hidden_at 이후 도착하면 read time에 자동 재출현.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RoomVisibilityService {

    private static final String HIDDEN_KEY_PREFIX = "chatflow:hidden:";

    private final StringRedisTemplate redisTemplate;
    private final RedisHealthTracker redisHealth;

    /**
     * 사용자가 방을 숨김. 멱등 -- 이미 hidden이면 timestamp 갱신.
     */
    public void hide(String userId, String roomId) {
        if (redisHealth.isCircuitOpen()) {
            log.warn("Redis circuit open -- hide 요청 무시: userId={}, roomId={}", userId, roomId);
            return;
        }
        try {
            String key = HIDDEN_KEY_PREFIX + userId;
            redisTemplate.opsForHash().put(key, roomId, Instant.now().toString());
            redisHealth.recordSuccess();
            log.info("DM 방 숨김: userId={}, roomId={}", userId, roomId);
        } catch (Exception e) {
            redisHealth.recordFailure(e);
            log.error("DM 방 숨김 실패: userId={}, roomId={}", userId, roomId, e);
        }
    }

    /**
     * 명시적 unhide (선택적 -- 보통은 새 메시지로 자동 재출현).
     */
    public void unhide(String userId, String roomId) {
        if (redisHealth.isCircuitOpen()) return;
        try {
            redisTemplate.opsForHash().delete(HIDDEN_KEY_PREFIX + userId, roomId);
            redisHealth.recordSuccess();
            log.info("DM 방 숨김 해제: userId={}, roomId={}", userId, roomId);
        } catch (Exception e) {
            redisHealth.recordFailure(e);
        }
    }

    /**
     * userId의 hidden roomId -> hidden_at 맵 조회.
     * Redis 장애 시 빈 맵 반환 (fail-open: 모두 보임).
     */
    public Map<String, Instant> getHiddenMap(String userId) {
        if (redisHealth.isCircuitOpen() || userId == null || userId.isBlank()) {
            return Map.of();
        }
        try {
            Map<Object, Object> raw = redisTemplate.opsForHash().entries(HIDDEN_KEY_PREFIX + userId);
            redisHealth.recordSuccess();
            if (raw.isEmpty()) return Map.of();

            Map<String, Instant> result = new HashMap<>();
            raw.forEach((k, v) -> {
                try {
                    result.put(k.toString(), Instant.parse(v.toString()));
                } catch (Exception ignored) {
                    // 잘못된 timestamp 무시 -- 해당 방은 visible로 처리
                }
            });
            return result;
        } catch (Exception e) {
            redisHealth.recordFailure(e);
            return Map.of();
        }
    }

    /**
     * room이 사용자에게 보이는지 판단.
     * hidden 처리된 방이라도 lastMessageAt > hidden_at이면 자동 재출현.
     */
    public boolean isVisible(ChatRoom room, Map<String, Instant> hiddenMap) {
        Instant hiddenAt = hiddenMap.get(room.getId());
        if (hiddenAt == null) return true; // 숨김 아님

        // lastMessageAt이 없으면 (메시지 없는 빈 방) hidden 유지
        if (room.getLastMessageAt() == null) return false;

        Instant lastMsgInstant = room.getLastMessageAt()
                .atZone(ZoneOffset.UTC).toInstant();
        return lastMsgInstant.isAfter(hiddenAt);
    }
}
