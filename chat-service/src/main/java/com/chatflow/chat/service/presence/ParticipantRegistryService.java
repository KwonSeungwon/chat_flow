package com.chatflow.chat.service.presence;

import com.chatflow.chat.entity.RoomMemberEntity;
import com.chatflow.chat.repository.RoomMemberRepository;
import com.chatflow.chat.service.ParticipantService;
import com.chatflow.common.dto.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Redis SET-backed participant registry + room_members backfill. Entry
 * format: "userId:sessionId:username".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ParticipantRegistryService {

    private static final String KEY_PREFIX = "chatflow:room:participants:";

    private final StringRedisTemplate redisTemplate;
    private final RoomMemberRepository roomMemberRepository;
    private final ParticipantService participantService;

    public void register(ChatMessage message, String sessionId) {
        String key = KEY_PREFIX + message.getChatRoomId();
        String safeUserId = message.getUserId() != null ? message.getUserId() : "anonymous";
        String safeSessionId = sessionId != null ? sessionId : "unknown";
        String entry = safeUserId + ":" + safeSessionId + ":" + message.getUsername();

        redisTemplate.opsForSet().add(key, entry);
        redisTemplate.expire(key, 7, TimeUnit.DAYS);
        syncParticipantCount(message.getChatRoomId());

        if (!safeUserId.equals("anonymous") &&
                !roomMemberRepository.existsByRoomIdAndUserId(message.getChatRoomId(), safeUserId)) {
            try {
                String safeUsername = message.getUsername() != null ? message.getUsername() : "anonymous";
                roomMemberRepository.save(RoomMemberEntity.builder()
                        .roomId(message.getChatRoomId())
                        .userId(safeUserId)
                        .username(safeUsername)
                        .joinedAt(LocalDateTime.now())
                        .build());
            } catch (DataIntegrityViolationException e) {
                log.info("RoomMember already exists (concurrent insert): roomId={} userId={}",
                        message.getChatRoomId(), safeUserId);
            }
        }
    }

    public Set<String> getRoomParticipantUserIds(String roomId) {
        Set<String> members = redisTemplate.opsForSet().members(KEY_PREFIX + roomId);
        if (members == null || members.isEmpty()) return Set.of();
        return members.stream().map(e -> e.split(":", 3)[0]).collect(Collectors.toSet());
    }

    public void removeSession(String roomId, String username, String sessionId) {
        String key = KEY_PREFIX + roomId;
        Set<String> members = redisTemplate.opsForSet().members(key);
        if (members == null) return;

        if (sessionId != null) {
            members.stream()
                    .filter(e -> e.contains(":" + sessionId + ":"))
                    .forEach(e -> redisTemplate.opsForSet().remove(key, e));
        } else {
            members.stream()
                    .filter(e -> e.endsWith(":" + username))
                    .forEach(e -> redisTemplate.opsForSet().remove(key, e));
        }
    }

    public boolean userStillPresent(String roomId, String username) {
        Set<String> remaining = redisTemplate.opsForSet().members(KEY_PREFIX + roomId);
        return remaining != null && remaining.stream().anyMatch(e -> e.endsWith(":" + username));
    }

    public void syncParticipantCount(String roomId) {
        Set<String> userIds = getRoomParticipantUserIds(roomId);
        participantService.setParticipantCount(roomId, userIds.size());
    }
}
