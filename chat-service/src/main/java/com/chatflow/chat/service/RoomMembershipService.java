package com.chatflow.chat.service;

import com.chatflow.chat.config.RedisHealthTracker;
import com.chatflow.chat.entity.RoomMemberEntity;
import com.chatflow.chat.entity.RoomRole;
import com.chatflow.chat.repository.RoomMemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Room membership lifecycle — seeding members on access grants, leaving,
 * and invite-message broadcasting.
 *
 * Split from ChatRoomService so the latter stays a pure room-CRUD/cache
 * service. Read paths and updates of room state stay there; anything that
 * touches the room_members table or broadcasts a system event lands here.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoomMembershipService {

    private final RoomMemberRepository roomMemberRepository;
    private final StringRedisTemplate redisTemplate;
    private final RedisHealthTracker redisHealth;
    private final SimpMessagingTemplate messagingTemplate;
    private final ParticipantService participantService;
    private final RoomCacheEvictor roomCacheEvictor;

    /**
     * Idempotently inserts a (roomId, userId) row into room_members.
     * Default role is MEMBER. Use the 4-arg overload to set OWNER/MODERATOR.
     */
    public void addMemberIfAbsent(String roomId, String userId, String username) {
        addMemberIfAbsent(roomId, userId, username, RoomRole.MEMBER);
    }

    /**
     * Idempotently inserts a (roomId, userId) row into room_members.
     * Called from every access-granting path (create, invite-join, password
     * verify, DM create) so the room_members table tracks every legitimate
     * member, not just users who happened to send a STOMP message.
     */
    public void addMemberIfAbsent(String roomId, String userId, String username, RoomRole role) {
        if (userId == null || userId.isBlank()) return;
        if (roomMemberRepository.existsByRoomIdAndUserId(roomId, userId)) return;
        try {
            roomMemberRepository.save(RoomMemberEntity.builder()
                    .roomId(roomId)
                    .userId(userId)
                    .username(username != null && !username.isBlank() ? username : userId)
                    .role(role != null ? role : RoomRole.MEMBER)
                    .joinedAt(LocalDateTime.now())
                    .build());
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Benign — concurrent insert race
            log.debug("Concurrent member insert race: room={} user={}", roomId, userId);
        }
    }

    public void sendInviteMessage(String roomId, String inviterName, String targetUsername) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "SYSTEM");
        msg.put("chatRoomId", roomId);
        msg.put("username", "SYSTEM");
        msg.put("messageId", UUID.randomUUID().toString());
        msg.put("timestamp", LocalDateTime.now().toString());
        msg.put("content", (inviterName != null ? inviterName : "누군가") +
                "님이 " + targetUsername + "님을 채팅방에 초대했습니다.");
        messagingTemplate.convertAndSend("/topic/chat/" + roomId, msg);
        log.info("Invite message sent: {} invited {} to room {}", inviterName, targetUsername, roomId);
    }

    @Transactional
    public void leaveRoom(String roomId, String userId, String username) {
        // Redis SET에서 해당 유저의 모든 세션 제거 (userId prefix로 매칭 -- 스푸핑 방지)
        String participantKey = "chatflow:room:participants:" + roomId;
        if (!redisHealth.isCircuitOpen()) {
            try {
                Set<String> members = redisTemplate.opsForSet().members(participantKey);
                if (members != null) {
                    members.stream()
                        .filter(e -> e.startsWith(userId + ":"))
                        .forEach(e -> redisTemplate.opsForSet().remove(participantKey, e));
                }
                redisHealth.recordSuccess();
            } catch (Exception e) {
                redisHealth.recordFailure(e);
            }
        }
        // 퇴장 시스템 메시지 브로드캐스트
        Map<String, Object> leaveMsg = new LinkedHashMap<>();
        leaveMsg.put("type", "LEAVE");
        leaveMsg.put("chatRoomId", roomId);
        leaveMsg.put("username", username);
        leaveMsg.put("messageId", UUID.randomUUID().toString());
        leaveMsg.put("timestamp", LocalDateTime.now().toString());
        leaveMsg.put("content", username + "님이 채팅방을 나갔습니다.");
        messagingTemplate.convertAndSend("/topic/chat/" + roomId, leaveMsg);
        // 참가자 수 동기화 (Redis SET 기반 unique user count로 설정 -- UserPresenceService와 동일 패턴)
        participantService.syncParticipantCountFromRedis(roomId);
        roomCacheEvictor.evict(roomId);
        log.info("User {} left room {} via REST API", username, roomId);
    }
}
