package com.chatflow.chat.controller;

import com.chatflow.chat.entity.ChatRoom;
import com.chatflow.chat.entity.RoomRole;
import com.chatflow.chat.exception.ForbiddenException;
import com.chatflow.chat.exception.UnauthorizedException;
import com.chatflow.chat.repository.RoomMemberRepository;
import com.chatflow.chat.service.room.ChatRoomService;
import com.chatflow.chat.service.room.RoomMembershipService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Shared 401/403 gate for room-scoped REST endpoints.
 *
 * Legacy bridge: pre-seeding patch, room_members was only populated on
 * STOMP join. To unbreak users who created/joined rooms before the
 * member-seeding fix landed, also accept room.createdBy == userId.
 * On hit, the missing row is backfilled as OWNER so the creator does
 * not lose moderation features (mute/ban) by landing as MEMBER.
 */
@Component
@RequiredArgsConstructor
public class RoomMembershipGuard {

    private final ChatRoomService chatRoomService;
    private final RoomMembershipService roomMembershipService;
    private final RoomMemberRepository roomMemberRepository;

    /**
     * Asserts the caller is authenticated AND a member of the room.
     * Throws UnauthorizedException (401) if userId is missing/blank,
     * ForbiddenException (403) if the caller is not a member.
     *
     * Legacy bridge: room.createdBy == userId is accepted as membership, and
     * the missing room_members row is backfilled as OWNER to preserve
     * moderation features for pre-seed creators.
     */
    public void requireMember(String roomId, String userId) {
        if (userId == null || userId.isBlank()) {
            throw new UnauthorizedException("인증이 필요합니다.");
        }
        if (roomMemberRepository.existsByRoomIdAndUserId(roomId, userId)) return;
        ChatRoom legacy = chatRoomService.getRoom(roomId).orElse(null);
        if (legacy != null && userId.equals(legacy.getCreatedBy())) {
            roomMembershipService.addMemberIfAbsent(roomId, userId, null, RoomRole.OWNER);
            return;
        }
        throw new ForbiddenException("방 멤버가 아닙니다.");
    }
}
