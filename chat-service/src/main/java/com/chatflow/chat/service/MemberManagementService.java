package com.chatflow.chat.service;

import com.chatflow.chat.entity.RoomMemberEntity;
import com.chatflow.chat.entity.RoomRole;
import com.chatflow.chat.exception.PermissionDeniedException;
import com.chatflow.chat.repository.RoomMemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemberManagementService {

    private static final int MIN_MUTE_MINUTES = 1;
    private static final int MAX_MUTE_MINUTES = 1440; // 24시간 — 1년 mute 같은 abuse 차단

    private final RoomMemberRepository roomMemberRepository;
    private final RoomPermissionService roomPermissionService;
    private final SimpMessagingTemplate messagingTemplate;
    private final MemberListBroadcaster memberListBroadcaster;

    @Transactional
    public void kickMember(String roomId, String actorUserId, String targetUserId) {
        roomPermissionService.requireNotDmRoom(roomId);
        roomPermissionService.requireRole(roomId, actorUserId, RoomRole.OWNER, RoomRole.MODERATOR);
        roomPermissionService.requireNotSelfTarget(actorUserId, targetUserId);

        RoomMemberEntity target = getTargetMember(roomId, targetUserId);
        validateNotTargetingOwner(target, actorUserId);

        String actorUsername = getActorUsername(roomId, actorUserId);

        roomMemberRepository.deleteByRoomIdAndUserId(roomId, targetUserId);
        log.info("Member kicked: roomId={}, target={}, by={}", roomId, targetUserId, actorUserId);

        sendKickedNotification(targetUserId, roomId, "KICKED", actorUsername, actorUserId);
        memberListBroadcaster.broadcast(roomId);
    }

    @Transactional
    public MuteResult muteMember(String roomId, String actorUserId, String targetUserId, int minutes) {
        if (minutes < MIN_MUTE_MINUTES || minutes > MAX_MUTE_MINUTES) {
            throw new IllegalArgumentException(
                    "뮤트 시간은 " + MIN_MUTE_MINUTES + "~" + MAX_MUTE_MINUTES + "분 범위만 허용됩니다. 요청값: " + minutes);
        }

        roomPermissionService.requireNotDmRoom(roomId);
        roomPermissionService.requireRole(roomId, actorUserId, RoomRole.OWNER, RoomRole.MODERATOR);
        roomPermissionService.requireNotSelfTarget(actorUserId, targetUserId);

        RoomMemberEntity target = getTargetMember(roomId, targetUserId);
        validateNotTargetingOwner(target, actorUserId);

        LocalDateTime mutedUntil = LocalDateTime.now().plusMinutes(minutes);
        target.setMutedUntil(mutedUntil);
        roomMemberRepository.save(target);
        log.info("Member muted: roomId={}, target={}, until={}, by={}",
                roomId, targetUserId, mutedUntil, actorUserId);

        String actorUsername = getActorUsername(roomId, actorUserId);
        sendMutedNotification(targetUserId, roomId, mutedUntil, actorUsername, actorUserId);
        memberListBroadcaster.broadcast(roomId);

        return new MuteResult(mutedUntil);
    }

    @Transactional
    public void unmuteMember(String roomId, String actorUserId, String targetUserId) {
        roomPermissionService.requireNotDmRoom(roomId);
        roomPermissionService.requireRole(roomId, actorUserId, RoomRole.OWNER, RoomRole.MODERATOR);
        roomPermissionService.requireNotSelfTarget(actorUserId, targetUserId);

        RoomMemberEntity target = getTargetMember(roomId, targetUserId);
        validateNotTargetingOwner(target, actorUserId);

        target.setMutedUntil(null);
        roomMemberRepository.save(target);
        log.info("Member unmuted: roomId={}, target={}, by={}", roomId, targetUserId, actorUserId);

        memberListBroadcaster.broadcast(roomId);
    }

    @Transactional
    public void changeRole(String roomId, String ownerUserId, String targetUserId, RoomRole newRole) {
        roomPermissionService.requireNotDmRoom(roomId);
        roomPermissionService.requireRole(roomId, ownerUserId, RoomRole.OWNER);
        roomPermissionService.requireNotSelfTarget(ownerUserId, targetUserId);

        if (newRole == RoomRole.OWNER) {
            throw new IllegalArgumentException(
                    "OWNER 역할 변경은 transferOwnership을 사용하세요.");
        }

        RoomMemberEntity target = getTargetMember(roomId, targetUserId);
        target.setRole(newRole);
        roomMemberRepository.save(target);
        log.info("Member role changed: roomId={}, target={}, newRole={}, by={}",
                roomId, targetUserId, newRole, ownerUserId);

        memberListBroadcaster.broadcast(roomId);
    }

    @Transactional
    public void transferOwnership(String roomId, String ownerUserId, String newOwnerUserId) {
        roomPermissionService.requireNotDmRoom(roomId);
        roomPermissionService.requireRole(roomId, ownerUserId, RoomRole.OWNER);
        roomPermissionService.requireNotSelfTarget(ownerUserId, newOwnerUserId);

        RoomMemberEntity currentOwner = getTargetMember(roomId, ownerUserId);
        RoomMemberEntity newOwner = getTargetMember(roomId, newOwnerUserId);

        currentOwner.setRole(RoomRole.MODERATOR);
        newOwner.setRole(RoomRole.OWNER);
        roomMemberRepository.save(currentOwner);
        roomMemberRepository.save(newOwner);
        log.info("Ownership transferred: roomId={}, from={}, to={}",
                roomId, ownerUserId, newOwnerUserId);

        memberListBroadcaster.broadcast(roomId);
    }

    // ── Internal helpers ─────────────────────────────────────────

    private RoomMemberEntity getTargetMember(String roomId, String targetUserId) {
        return roomMemberRepository.findByRoomIdAndUserId(roomId, targetUserId)
                .orElseThrow(() -> new PermissionDeniedException(
                        "대상 사용자가 채팅방의 멤버가 아닙니다. roomId=" + roomId
                                + ", userId=" + targetUserId));
    }

    /**
     * MOD cannot target OWNER. OWNER also cannot self-kick (already blocked by requireNotSelfTarget),
     * but if somehow reached, OWNER cannot be removed via kick — only room deletion.
     */
    private void validateNotTargetingOwner(RoomMemberEntity target, String actorUserId) {
        if (target.getRole() == RoomRole.OWNER) {
            log.warn("Cannot target OWNER: roomId={} actor={} target={}",
                    target.getRoomId(), actorUserId, target.getUserId());
            throw new PermissionDeniedException(
                    "OWNER에게는 이 액션을 수행할 수 없습니다. 방 삭제를 사용하세요.");
        }
    }

    private String getActorUsername(String roomId, String actorUserId) {
        return roomMemberRepository.findByRoomIdAndUserId(roomId, actorUserId)
                .map(RoomMemberEntity::getUsername)
                .orElse("unknown");
    }

    private void sendKickedNotification(String targetUserId, String roomId,
                                        String reason, String actorUsername, String actorUserId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("roomId", roomId);
        payload.put("reason", reason);
        payload.put("by", actorUsername);
        payload.put("byUserId", actorUserId);
        messagingTemplate.convertAndSendToUser(targetUserId, "/queue/kicked", payload);
    }

    private void sendMutedNotification(String targetUserId, String roomId,
                                       LocalDateTime mutedUntil, String actorUsername, String actorUserId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("roomId", roomId);
        payload.put("mutedUntil", mutedUntil.toString());
        payload.put("by", actorUsername);
        payload.put("byUserId", actorUserId);
        messagingTemplate.convertAndSendToUser(targetUserId, "/queue/muted", payload);
    }
}
