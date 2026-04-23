package com.chatflow.chat.service;

import com.chatflow.chat.config.RedisHealthTracker;
import com.chatflow.chat.entity.ChatRoom;
import com.chatflow.chat.entity.RoomType;
import com.chatflow.chat.repository.ChatRoomRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DmRoomService {

    private static final String ROOM_CACHE_KEY = "chatflow:room:";
    private static final String ROOMS_LIST_KEY = "chatflow:rooms:list";

    private final ChatRoomRepository chatRoomRepository;
    private final StringRedisTemplate redisTemplate;
    private final RedisHealthTracker redisHealth;

    @Transactional
    public ChatRoom createOrFindDmRoom(String userId1, String username1, String userId2, String username2) {
        // DM room name is canonical: sorted usernames
        String name1 = "DM:" + username1 + "," + username2;
        String name2 = "DM:" + username2 + "," + username1;
        List<ChatRoom> existing = chatRoomRepository.findDmRoom(name1, name2);
        if (!existing.isEmpty()) return existing.get(0);

        ChatRoom dm = ChatRoom.builder()
                .id(UUID.randomUUID().toString())
                .name(name1)
                .description(username1 + "님과 " + username2 + "님의 대화")
                .roomType(RoomType.DIRECT)
                .maxParticipants(2)
                .participantCount(0)
                .createdAt(LocalDateTime.now())
                .build();
        try {
            ChatRoom saved = chatRoomRepository.save(dm);
            evictRoomCaches(saved.getId());
            return saved;
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // TOCTOU: 동시 요청으로 중복 생성 시 기존 방 반환
            log.warn("DM room race condition detected, re-querying: {} <-> {}", username1, username2);
            List<ChatRoom> retry = chatRoomRepository.findDmRoom(name1, name2);
            if (!retry.isEmpty()) return retry.get(0);
            throw e;
        }
    }

    private void evictRoomCaches(String roomId) {
        if (redisHealth.isCircuitOpen()) return;
        try {
            redisTemplate.delete(ROOM_CACHE_KEY + roomId);
            redisTemplate.delete(ROOMS_LIST_KEY);
            redisHealth.recordSuccess();
        } catch (Exception e) {
            redisHealth.recordFailure(e);
        }
    }
}
