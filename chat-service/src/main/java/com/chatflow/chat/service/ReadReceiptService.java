package com.chatflow.chat.service;

import com.chatflow.common.dto.ReadReceipt;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReadReceiptService {

    private static final String READ_KEY_PREFIX = "chatflow:read:";
    private static final long READ_TTL_HOURS = 24;

    private final StringRedisTemplate redisTemplate;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * 특정 채팅방에서 각 사용자의 마지막 읽은 메시지 ID를 반환한다.
     */
    public Map<String, String> getRoomReadPositions(String roomId) {
        String pattern = READ_KEY_PREFIX + roomId + ":*";
        Set<String> keys = new HashSet<>();
        // SCAN 대신 KEYS O(N) 블로킹 방지 — SCAN으로 순회
        try (Cursor<String> cursor = redisTemplate.scan(
                ScanOptions.scanOptions().match(pattern).count(100).build())) {
            cursor.forEachRemaining(keys::add);
        } catch (Exception e) {
            log.warn("Redis SCAN failed for pattern {}: {}", pattern, e.getMessage());
        }
        Map<String, String> positions = new java.util.LinkedHashMap<>();
        for (String key : keys) {
            // key = chatflow:read:{roomId}:{userId}
            String userId = key.substring(key.lastIndexOf(':') + 1);
            String lastReadMsgId = redisTemplate.opsForValue().get(key);
            if (lastReadMsgId != null) {
                positions.put(userId, lastReadMsgId);
            }
        }
        return positions;
    }

    public void markRead(String roomId, String userId, String username, String lastReadMessageId) {
        String key = READ_KEY_PREFIX + roomId + ":" + userId;
        redisTemplate.opsForValue().set(key, lastReadMessageId, READ_TTL_HOURS, TimeUnit.HOURS);
        // 미읽은 카운트 계산에 사용할 타임스탬프 저장
        String atKey = "chatflow:readat:" + roomId + ":" + userId;
        redisTemplate.opsForValue().set(atKey, LocalDateTime.now().toString(), READ_TTL_HOURS, TimeUnit.HOURS);

        ReadReceipt receipt = ReadReceipt.builder()
                .userId(userId)
                .username(username)
                .roomId(roomId)
                .lastReadMessageId(lastReadMessageId)
                .timestamp(LocalDateTime.now())
                .build();

        messagingTemplate.convertAndSend("/topic/chat/" + roomId + "/read-receipts", receipt);
        log.debug("Read receipt recorded: room={}, user={}, messageId={}", roomId, userId, lastReadMessageId);
    }
}
