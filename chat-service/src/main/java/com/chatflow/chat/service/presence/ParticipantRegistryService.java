package com.chatflow.chat.service.presence;

import com.chatflow.chat.entity.RoomMemberEntity;
import com.chatflow.chat.repository.RoomMemberRepository;
import com.chatflow.chat.service.room.ParticipantService;
import com.chatflow.chat.service.room.RoomAccessPolicy;
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
 * Redis SET-backed participant registry + policy-gated room_members
 * auto-join row. Entry format: "userId:sessionId:username".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ParticipantRegistryService {

    private static final String KEY_PREFIX = "chatflow:room:participants:";

    private final StringRedisTemplate redisTemplate;
    private final RoomMemberRepository roomMemberRepository;
    private final ParticipantService participantService;
    private final RoomAccessPolicy roomAccessPolicy;

    /**
     * Registers the session's presence and, for an authenticated user, the
     * implicit room_members row — but only if {@link RoomAccessPolicy#canAutoJoin}
     * allows it. The policy is re-checked here (not just in the STOMP handler)
     * because {@code RoomFullnessService} may have redirected the message to a
     * different room since the caller's check.
     *
     * @return the policy decision; when denied nothing was written to Redis
     *         or room_members.
     */
    public RoomAccessPolicy.JoinDecision register(ChatMessage message, String sessionId) {
        String roomId = message.getChatRoomId();
        String safeUserId = message.getUserId() != null ? message.getUserId() : "anonymous";
        boolean authenticated = !safeUserId.equals("anonymous");

        RoomAccessPolicy.JoinDecision decision =
                roomAccessPolicy.canAutoJoin(roomId, authenticated ? safeUserId : null);
        if (!decision.allowed()) {
            log.warn("Join refused by access policy: room={} user={} reason={}",
                    roomId, safeUserId, decision.reason());
            return decision;
        }

        String key = KEY_PREFIX + roomId;
        String safeSessionId = sessionId != null ? sessionId : "unknown";
        String entry = safeUserId + ":" + safeSessionId + ":" + message.getUsername();

        redisTemplate.opsForSet().add(key, entry);
        redisTemplate.expire(key, 7, TimeUnit.DAYS);
        syncParticipantCount(roomId);

        if (authenticated && !roomMemberRepository.existsByRoomIdAndUserId(roomId, safeUserId)) {
            try {
                String safeUsername = message.getUsername() != null ? message.getUsername() : "anonymous";
                roomMemberRepository.save(RoomMemberEntity.builder()
                        .roomId(roomId)
                        .userId(safeUserId)
                        .username(safeUsername)
                        .joinedAt(LocalDateTime.now())
                        .build());
            } catch (DataIntegrityViolationException e) {
                log.info("RoomMember already exists (concurrent insert): roomId={} userId={}",
                        roomId, safeUserId);
            }
        }
        return decision;
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
