package com.chatflow.chat.service.read;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReadReceiptService {

    /**
     * Redis key for per-room read positions hash.
     * Structure: HASH  chatflow:read:{roomId}  field=userId  value=lastReadMessageId
     *
     * <p>Migration note: prior versions stored read positions as individual string keys
     * {@code chatflow:read:{roomId}:{userId}}. Those keys are NOT migrated — they expire
     * within 24h. During the brief post-deploy window, some pre-existing positions may
     * not appear until users send a new read receipt. This is acceptable because read
     * positions are ephemeral UI state.
     */
    private static final String READ_KEY_PREFIX = "chatflow:read:";
    private static final long READ_TTL_HOURS = 24;

    private final StringRedisTemplate redisTemplate;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Returns each user's last-read message ID for the given room.
     * Single HGETALL — O(room members), no keyspace scan.
     */
    public Map<String, String> getRoomReadPositions(String roomId) {
        String hashKey = READ_KEY_PREFIX + roomId;
        Map<String, String> positions = new LinkedHashMap<>();
        try {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(hashKey);
            for (Map.Entry<Object, Object> entry : entries.entrySet()) {
                positions.put((String) entry.getKey(), (String) entry.getValue());
            }
        } catch (Exception e) {
            log.warn("Redis HGETALL failed for key {}: {}", hashKey, e.getMessage());
        }
        return positions;
    }

    /**
     * Returns a single user's last-read message ID for the given room.
     * Single HGET — O(1).
     */
    public String getLastReadMessageId(String roomId, String userId) {
        String hashKey = READ_KEY_PREFIX + roomId;
        Object value = redisTemplate.opsForHash().get(hashKey, userId);
        return value != null ? (String) value : null;
    }

    /**
     * readAt 타임스탬프만 갱신 (lastReadMessageId 없이).
     * 방 입장 시점에 로컬 메시지가 아직 로드되지 않아 lastReadMessageId를 모를 때 사용.
     * unread count는 readAt 기준으로 계산되므로 이것만으로 충분히 동작.
     */
    public void updateReadAt(String roomId, String userId) {
        String atKey = "chatflow:readat:" + roomId + ":" + userId;
        redisTemplate.opsForValue().set(atKey, LocalDateTime.now().toString(), READ_TTL_HOURS, TimeUnit.HOURS);
        log.debug("readAt updated (no lastRead): room={}, user={}", roomId, userId);
    }

    public void markRead(String roomId, String userId, String username, String lastReadMessageId) {
        String hashKey = READ_KEY_PREFIX + roomId;

        // HSET chatflow:read:{roomId} {userId} {lastReadMessageId}
        redisTemplate.opsForHash().put(hashKey, userId, lastReadMessageId);
        // Refresh hash TTL on each write so the hash lives as long as the room is active.
        // HSET + EXPIRE are not atomic: a crash between them can leave a no-TTL hash, but
        // the next markRead in this room re-arms the TTL (self-healing), so it never leaks
        // for an active room — not worth an EVAL for ephemeral read-position state.
        redisTemplate.expire(hashKey, READ_TTL_HOURS, TimeUnit.HOURS);

        // 미읽은 카운트 계산에 사용할 타임스탬프 저장 (per-user string key — unchanged)
        String atKey = "chatflow:readat:" + roomId + ":" + userId;
        redisTemplate.opsForValue().set(atKey, LocalDateTime.now().toString(), READ_TTL_HOURS, TimeUnit.HOURS);

        // 프론트엔드가 메시지별 readCount를 계산할 수 있도록 전체 readPositions를 함께 전송
        Map<String, String> positions = getRoomReadPositions(roomId);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", userId);
        payload.put("username", username);
        payload.put("roomId", roomId);
        payload.put("messageId", lastReadMessageId);           // frontend 호환 (기존 lastReadMessageId alias)
        payload.put("lastReadMessageId", lastReadMessageId);
        payload.put("readCount", positions.size());             // 방 전체 읽음 인원수 (하위 호환용)
        payload.put("positions", positions);                    // userId -> lastReadMessageId
        payload.put("timestamp", LocalDateTime.now().toString());

        messagingTemplate.convertAndSend("/topic/chat/" + roomId + "/read-receipts", payload);
        log.debug("Read receipt recorded: room={}, user={}, messageId={}, positions={}", roomId, userId, lastReadMessageId, positions.size());
    }
}
