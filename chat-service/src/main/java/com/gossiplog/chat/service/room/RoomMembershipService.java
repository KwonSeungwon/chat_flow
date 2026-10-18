package com.gossiplog.chat.service.room;

import com.gossiplog.chat.config.RedisHealthTracker;
import com.gossiplog.chat.entity.RoomMemberEntity;
import com.gossiplog.chat.entity.RoomRole;
import com.gossiplog.chat.event.RoomMemberRemovedEvent;
import com.gossiplog.chat.repository.RoomMemberRepository;
import com.gossiplog.chat.result.ChatErrorCode;
import com.gossiplog.chat.result.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
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
    private final RoomAccessPolicy roomAccessPolicy;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * The only entry point for user-initiated membership grants (invite link,
     * password verify, DM create). Asks {@link RoomAccessPolicy#canGrant} first
     * and inserts a MEMBER row only when the policy allows it.
     *
     * @return the policy decision; when denied, no row is written.
     */
    public RoomAccessPolicy.JoinDecision grantMembership(String roomId, String userId, String username,
                                                         RoomAccessPolicy.GrantPath via) {
        RoomAccessPolicy.JoinDecision decision = roomAccessPolicy.canGrant(roomId, userId, via);
        if (!decision.allowed()) {
            log.info("Membership grant denied: room={} user={} via={} reason={}",
                    roomId, userId, via, decision.reason());
            return decision;
        }
        addMemberIfAbsent(roomId, userId, username, RoomRole.MEMBER);
        return decision;
    }

    /**
     * Idempotently inserts a (roomId, userId) row into room_members.
     * Package-private on purpose: outside this package only
     * {@link #grantMembership} may create rows. The one in-package direct
     * caller is owner seeding in {@link ChatRoomService#createRoom}.
     */
    void addMemberIfAbsent(String roomId, String userId, String username, RoomRole role) {
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
    public Result<Void, ChatErrorCode> leaveRoom(String roomId, String userId, String username) {
        if (!roomMemberRepository.existsByRoomIdAndUserId(roomId, userId)) {
            return Result.err(ChatErrorCode.NOT_FOUND, "방 멤버가 아닙니다.");
        }
        // DB row 삭제 — source of truth; Redis/broadcast 전에 수행
        roomMemberRepository.deleteByRoomIdAndUserId(roomId, userId);
        eventPublisher.publishEvent(new RoomMemberRemovedEvent(
                roomId, userId, RoomMemberRemovedEvent.Reason.LEFT));
        // Redis SET에서 해당 유저의 모든 세션 제거 (userId prefix로 매칭 -- 스푸핑 방지)
        String participantKey = "gossiplog:room:participants:" + roomId;
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
        return Result.<ChatErrorCode>ok();
    }
}
