package com.chatflow.chat.service;

import com.chatflow.chat.config.RedisHealthTracker;
import com.chatflow.chat.repository.ChatMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class UnreadCountService {

    private final ChatMessageRepository chatMessageRepository;
    private final StringRedisTemplate redisTemplate;
    private final RedisHealthTracker redisHealth;

    public Map<String, Long> getUnreadCounts(String userId, List<String> roomIds) {
        if (roomIds.isEmpty()) return Collections.emptyMap();

        List<String> keys = roomIds.stream()
                .map(id -> "chatflow:readat:" + id + ":" + userId)
                .collect(Collectors.toList());

        List<String> values;
        try {
            if (redisHealth.isCircuitOpen()) {
                values = null;
            } else {
                values = redisTemplate.opsForValue().multiGet(keys);
                redisHealth.recordSuccess();
            }
        } catch (Exception e) {
            redisHealth.recordFailure(e);
            values = null;
        }

        // readAt 기준으로 2그룹 분리
        List<String> unreadAllRoomIds = new ArrayList<>();          // readAt null: 전체 카운트
        Map<String, LocalDateTime> readAtByRoom = new LinkedHashMap<>();  // readAt 존재
        for (int i = 0; i < roomIds.size(); i++) {
            String roomId = roomIds.get(i);
            String readAtStr = (values != null) ? values.get(i) : null;
            if (readAtStr == null) {
                unreadAllRoomIds.add(roomId);
            } else {
                try {
                    readAtByRoom.put(roomId, LocalDateTime.parse(readAtStr));
                } catch (Exception e) {
                    unreadAllRoomIds.add(roomId);  // 파싱 실패 시 전체 카운트로 대체
                }
            }
        }

        Map<String, Long> result = new LinkedHashMap<>();
        // roomIds 순서 보존을 위해 모든 roomId를 0L로 초기화
        for (String roomId : roomIds) result.put(roomId, 0L);

        // Group A: readAt null -- 단일 배치 쿼리
        if (!unreadAllRoomIds.isEmpty()) {
            LocalDateTime epoch = LocalDateTime.of(2000, 1, 1, 0, 0);
            try {
                List<Object[]> rows = chatMessageRepository.countNewChatMessagesBatch(unreadAllRoomIds, epoch);
                for (Object[] row : rows) {
                    String roomId = (String) row[0];
                    Long count = ((Number) row[1]).longValue();
                    result.put(roomId, count);
                }
            } catch (Exception e) {
                // 배치 쿼리 실패 시 개별 쿼리 폴백
                for (String roomId : unreadAllRoomIds) {
                    try {
                        result.put(roomId, chatMessageRepository.countNewChatMessages(roomId, epoch));
                    } catch (Exception ex) {
                        result.put(roomId, 0L);
                    }
                }
            }
        }

        // Group B: readAt 존재 -- 각 roomId별 개별 쿼리 (cutoff 다름)
        for (Map.Entry<String, LocalDateTime> entry : readAtByRoom.entrySet()) {
            try {
                result.put(entry.getKey(),
                        chatMessageRepository.countNewChatMessages(entry.getKey(), entry.getValue()));
            } catch (Exception e) {
                result.put(entry.getKey(), 0L);
            }
        }

        return result;
    }
}
