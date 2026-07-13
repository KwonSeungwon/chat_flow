package com.chatflow.chat.service.read;

import com.chatflow.chat.repository.ChatMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class UnreadCountService {

    private final ChatMessageRepository chatMessageRepository;

    public Map<String, Long> getUnreadCounts(String userId, List<String> roomIds) {
        if (roomIds.isEmpty()) return Collections.emptyMap();

        // Initialize all requested roomIds to 0L, preserving request order
        Map<String, Long> result = new LinkedHashMap<>();
        for (String roomId : roomIds) {
            result.put(roomId, 0L);
        }

        // Single grouped query: theta join room_members on last_read_at cursor
        try {
            List<Object[]> rows = chatMessageRepository.countUnreadByCursor(userId, roomIds);
            for (Object[] row : rows) {
                String roomId = (String) row[0];
                Long count = ((Number) row[1]).longValue();
                result.put(roomId, count);
            }
        } catch (Exception e) {
            // Fail-soft: endpoint must not 500 — return all-zeros map
            log.warn("countUnreadByCursor query failed — returning all-zeros: {}", e.getMessage());
        }

        return result;
    }
}
