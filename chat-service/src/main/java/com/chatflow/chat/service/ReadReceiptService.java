package com.chatflow.chat.service;

import com.chatflow.common.dto.ReadReceipt;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReadReceiptService {

    private static final String READ_KEY_PREFIX = "chatflow:read:";
    private static final long READ_TTL_HOURS = 24;

    private final StringRedisTemplate redisTemplate;
    private final SimpMessagingTemplate messagingTemplate;

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
