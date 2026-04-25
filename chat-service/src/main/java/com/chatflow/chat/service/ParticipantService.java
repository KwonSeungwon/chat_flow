package com.chatflow.chat.service;

import com.chatflow.chat.config.RedisHealthTracker;
import com.chatflow.chat.entity.ChatRoom;
import com.chatflow.chat.repository.ChatRoomRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ParticipantService {

    private static final int MAX_PARTICIPANTS = 10;

    private final ChatRoomRepository chatRoomRepository;
    private final StringRedisTemplate redisTemplate;
    private final RedisHealthTracker redisHealth;

    @Transactional
    public void incrementParticipantCount(String roomId) {
        chatRoomRepository.incrementParticipantCount(roomId);
    }

    @Transactional
    public void decrementParticipantCount(String roomId) {
        chatRoomRepository.decrementParticipantCount(roomId);
    }

    @Transactional
    public void setParticipantCount(String roomId, int count) {
        chatRoomRepository.findById(roomId).ifPresent(room ->
            room.setParticipantCount(count)
        );
    }

    @Transactional(readOnly = true)
    public boolean isRoomFull(String roomId) {
        try {
            return chatRoomRepository.isRoomFull(roomId);
        } catch (Exception e) {
            log.warn("isRoomFull 조회 실패 -- 안전을 위해 만석으로 처리: {}", roomId, e);
            return true;
        }
    }

    @Transactional
    public ChatRoom findOrCreateAvailableRoom(String baseName) {
        String escapedPattern = baseName.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        List<ChatRoom> available = chatRoomRepository.findAvailableByBaseName(baseName, escapedPattern);

        if (!available.isEmpty()) {
            return available.get(0);
        }

        long count = (long) chatRoomRepository.findByBaseName(baseName, escapedPattern).size();

        String newName = ChatRoom.nextOverflowName(baseName, count);
        ChatRoom newRoom = ChatRoom.builder()
                .id("room_" + UUID.randomUUID().toString())
                .name(newName)
                .description(baseName + " 채팅방 (자동 생성)")
                .color("#6366f1")
                .participantCount(0)
                .maxParticipants(MAX_PARTICIPANTS)
                .createdAt(LocalDateTime.now())
                .build();

        try {
            ChatRoom saved = chatRoomRepository.save(newRoom);
            log.info("Auto-created overflow room: {} ({})", saved.getName(), saved.getId());
            return saved;
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            log.warn("Concurrent room creation detected for '{}', returning existing room", newName);
            List<ChatRoom> retry = chatRoomRepository.findByBaseName(newName,
                    newName.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_"));
            if (!retry.isEmpty()) return retry.get(0);
            throw e;
        }
    }

    /**
     * Redis SET에서 unique user count를 계산하여 DB participantCount를 동기화.
     * UserPresenceService.syncParticipantCount와 동일한 패턴.
     */
    public void syncParticipantCountFromRedis(String roomId) {
        String participantKey = "chatflow:room:participants:" + roomId;
        int uniqueUserCount = 0;
        if (!redisHealth.isCircuitOpen()) {
            try {
                Set<String> members = redisTemplate.opsForSet().members(participantKey);
                if (members != null) {
                    uniqueUserCount = (int) members.stream()
                            .map(e -> e.split(":")[0])
                            .distinct()
                            .count();
                }
                redisHealth.recordSuccess();
            } catch (Exception e) {
                redisHealth.recordFailure(e);
                // Redis 실패 시 fallback으로 decrement 사용
                decrementParticipantCount(roomId);
                return;
            }
        } else {
            // circuit open 시 fallback으로 decrement 사용
            decrementParticipantCount(roomId);
            return;
        }
        setParticipantCount(roomId, uniqueUserCount);
    }
}
