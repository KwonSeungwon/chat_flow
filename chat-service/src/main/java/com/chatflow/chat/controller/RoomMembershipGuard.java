package com.chatflow.chat.controller;

import com.chatflow.chat.entity.ChatRoom;
import com.chatflow.chat.entity.RoomRole;
import com.chatflow.chat.repository.RoomMemberRepository;
import com.chatflow.chat.service.ChatRoomService;
import com.chatflow.chat.service.RoomMembershipService;
import com.chatflow.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
     * Returns null when the caller is authorized, otherwise the failure
     * ResponseEntity (401 if unauthenticated, 403 if not a member).
     */
    public ResponseEntity<ApiResponse<?>> requireMember(String roomId, String userId) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("인증이 필요합니다."));
        }
        if (roomMemberRepository.existsByRoomIdAndUserId(roomId, userId)) return null;
        ChatRoom legacy = chatRoomService.getRoom(roomId).orElse(null);
        if (legacy != null && userId.equals(legacy.getCreatedBy())) {
            roomMembershipService.addMemberIfAbsent(roomId, userId, null, RoomRole.OWNER);
            return null;
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("방 멤버가 아닙니다."));
    }
}
