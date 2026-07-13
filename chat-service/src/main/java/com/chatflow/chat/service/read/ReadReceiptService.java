package com.chatflow.chat.service.read;

import com.chatflow.chat.config.RedisHealthTracker;
import com.chatflow.chat.repository.RoomMemberRepository;
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
    private final RedisHealthTracker redisHealth;
    private final RoomMemberRepository roomMemberRepository;

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
     * Single HGET — O(1). Fail-soft: returns null on Redis outage.
     */
    public String getLastReadMessageId(String roomId, String userId) {
        if (redisHealth.isCircuitOpen()) {
            log.debug("Redis circuit open — skipping getLastReadMessageId: room={}, user={}", roomId, userId);
            return null;
        }
        String hashKey = READ_KEY_PREFIX + roomId;
        try {
            Object value = redisTemplate.opsForHash().get(hashKey, userId);
            redisHealth.recordSuccess();
            return value != null ? (String) value : null;
        } catch (Exception e) {
            redisHealth.recordFailure(e);
            log.debug("Redis HGET failed for key {} field {}: {}", hashKey, userId, e.getMessage());
            return null;
        }
    }

    /**
     * readAt 타임스탬프만 갱신 (lastReadMessageId 없이).
     * 방 입장 시점에 로컬 메시지가 아직 로드되지 않아 lastReadMessageId를 모를 때 사용.
     * unread count는 room_members.last_read_at 기준으로 계산되므로 이것만으로 충분히 동작.
     */
    public void updateReadAt(String roomId, String userId) {
        try {
            roomMemberRepository.touchLastReadAt(roomId, userId, LocalDateTime.now());
            log.debug("readAt cursor updated (DB): room={}, user={}", roomId, userId);
        } catch (Exception e) {
            log.warn("DB cursor touch failed in updateReadAt — swallowed: room={}, user={}, error={}",
                    roomId, userId, e.getMessage());
        }
    }

    public void markRead(String roomId, String userId, String username, String lastReadMessageId) {
        // Step 1: DB cursor touch — FIRST, in its own try/catch (fail-soft).
        // A DB blip must not kill the STOMP frame or prevent the Redis positions write.
        try {
            roomMemberRepository.touchLastReadAt(roomId, userId, LocalDateTime.now());
            log.debug("readAt cursor touched (DB): room={}, user={}", roomId, userId);
        } catch (Exception e) {
            log.warn("DB cursor touch failed in markRead — continuing: room={}, user={}, error={}",
                    roomId, userId, e.getMessage());
        }

        // Step 2: Redis positions hash — unchanged #18 circuit-breaker/fail-soft structure
        if (redisHealth.isCircuitOpen()) {
            log.debug("Redis circuit open — skipping markRead positions: room={}, user={}", roomId, userId);
            return;
        }

        String hashKey = READ_KEY_PREFIX + roomId;

        // All Redis writes are in one circuit/try block. If any write fails,
        // we skip the broadcast and return — broadcasting after a failed write would
        // push a stale/empty positions map to every client and could visually wipe
        // read-state. Skipping the broadcast is a cleaner degraded mode: read receipts
        // simply pause while Redis is down and self-heal on the next successful markRead.
        // getRoomReadPositions (already fail-soft) would return empty during the outage anyway.
        try {
            // HSET chatflow:read:{roomId} {userId} {lastReadMessageId}
            redisTemplate.opsForHash().put(hashKey, userId, lastReadMessageId);
            // Refresh hash TTL on each write so the hash lives as long as the room is active.
            // HSET + EXPIRE are not atomic: a crash between them can leave a no-TTL hash, but
            // the next markRead in this room re-arms the TTL (self-healing), so it never leaks
            // for an active room — not worth an EVAL for ephemeral read-position state.
            redisTemplate.expire(hashKey, READ_TTL_HOURS, TimeUnit.HOURS);

            redisHealth.recordSuccess();
        } catch (Exception e) {
            redisHealth.recordFailure(e);
            log.debug("Redis write failed in markRead — skipping broadcast: room={}, user={}, error={}",
                    roomId, userId, e.getMessage());
            return;
        }

        // 프론트엔드가 메시지별 readCount를 계산할 수 있도록 전체 readPositions를 함께 전송
        Map<String, String> positions = getRoomReadPositions(roomId);

        // The current user's HSET succeeded milliseconds earlier, so a truly empty
        // HGETALL implies a read-back failure (getRoomReadPositions is fail-soft).
        // Broadcasting positions={} would make every client REPLACE its readPositions
        // with an empty map, wiping all read markers — the same stale-broadcast hazard
        // this method's write-path guard already prevents.
        if (positions.isEmpty()) {
            log.debug("Read-back returned empty positions after a successful write — skipping broadcast (room={})", roomId);
            return;
        }

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
