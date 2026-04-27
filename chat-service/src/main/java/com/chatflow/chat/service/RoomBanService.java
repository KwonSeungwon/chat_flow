package com.chatflow.chat.service;

import com.chatflow.chat.entity.RoomBanEntity;
import com.chatflow.chat.entity.RoomMemberEntity;
import com.chatflow.chat.entity.RoomRole;
import com.chatflow.chat.exception.PermissionDeniedException;
import com.chatflow.chat.repository.RoomBanRepository;
import com.chatflow.chat.repository.RoomMemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoomBanService {

    private final RoomBanRepository roomBanRepository;
    private final RoomMemberRepository roomMemberRepository;
    private final RoomPermissionService roomPermissionService;
    private final SimpMessagingTemplate messagingTemplate;
    private final MemberListBroadcaster memberListBroadcaster;

    /**
     * Ban a user from the room: kick + insert ban row (single transaction).
     * Does NOT call MemberManagementService.kickMember to avoid circular permission checks.
     */
    @Transactional
    public void banUser(String roomId, String actorUserId, String targetUserId, String reason) {
        roomPermissionService.requireNotDmRoom(roomId);
        roomPermissionService.requireRole(roomId, actorUserId, RoomRole.OWNER, RoomRole.MODERATOR);
        roomPermissionService.requireNotSelfTarget(actorUserId, targetUserId);

        // Validate target is not OWNER
        RoomMemberEntity target = roomMemberRepository.findByRoomIdAndUserId(roomId, targetUserId)
                .orElse(null);
        if (target != null && target.getRole() == RoomRole.OWNER) {
            log.warn("Cannot ban OWNER: roomId={} actor={} target={}",
                    roomId, actorUserId, targetUserId);
            throw new PermissionDeniedException(
                    "OWNER를 ban할 수 없습니다.");
        }

        String actorUsername = roomMemberRepository.findByRoomIdAndUserId(roomId, actorUserId)
                .map(RoomMemberEntity::getUsername)
                .orElse("unknown");

        // Kick: remove from room members (if they are a member)
        if (target != null) {
            roomMemberRepository.deleteByRoomIdAndUserId(roomId, targetUserId);
        }

        // Insert ban row
        RoomBanEntity ban = RoomBanEntity.builder()
                .roomId(roomId)
                .userId(targetUserId)
                .bannedBy(actorUserId)
                .reason(reason)
                .bannedAt(LocalDateTime.now())
                .build();
        roomBanRepository.save(ban);
        log.info("User banned: roomId={}, target={}, by={}, reason={}",
                roomId, targetUserId, actorUserId, reason);

        // STOMP: notify the banned user
        Map<String, Object> kickedPayload = new LinkedHashMap<>();
        kickedPayload.put("roomId", roomId);
        kickedPayload.put("reason", "BANNED");
        kickedPayload.put("by", actorUsername);
        kickedPayload.put("byUserId", actorUserId);
        messagingTemplate.convertAndSendToUser(targetUserId, "/queue/kicked", kickedPayload);

        // STOMP: broadcast updated member list
        memberListBroadcaster.broadcast(roomId);
    }

    @Transactional
    public void unbanUser(String roomId, String actorUserId, String targetUserId) {
        roomPermissionService.requireNotDmRoom(roomId);
        roomPermissionService.requireNotSelfTarget(actorUserId, targetUserId);
        roomPermissionService.requireRole(roomId, actorUserId, RoomRole.OWNER, RoomRole.MODERATOR);

        roomBanRepository.deleteByRoomIdAndUserId(roomId, targetUserId);
        log.info("User unbanned: roomId={}, target={}, by={}", roomId, targetUserId, actorUserId);
    }

    public boolean isBanned(String roomId, String userId) {
        return roomBanRepository.existsByRoomIdAndUserId(roomId, userId);
    }

    @Transactional(readOnly = true)
    public List<RoomBanEntity> listBans(String roomId, String actorUserId) {
        roomPermissionService.requireNotDmRoom(roomId);
        roomPermissionService.requireRole(roomId, actorUserId, RoomRole.OWNER, RoomRole.MODERATOR);
        return roomBanRepository.findByRoomId(roomId);
    }
}
