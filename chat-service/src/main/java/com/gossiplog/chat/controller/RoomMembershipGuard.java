package com.gossiplog.chat.controller;

import com.gossiplog.chat.exception.ForbiddenException;
import com.gossiplog.chat.exception.UnauthorizedException;
import com.gossiplog.chat.repository.RoomMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Shared 401/403 gate for room-scoped REST endpoints.
 *
 * <p>Membership is exactly "a room_members row exists". The former
 * createdBy legacy bridge (with OWNER backfill) is gone: it let a kicked or
 * banned creator silently re-grant themselves OWNER. Rooms that predate
 * member seeding are covered by Flyway V10/V16 owner backfills instead.
 */
@Component
@RequiredArgsConstructor
public class RoomMembershipGuard {

    private final RoomMemberRepository roomMemberRepository;

    /**
     * Asserts the caller is authenticated AND a member of the room.
     * Throws UnauthorizedException (401) if userId is missing/blank,
     * ForbiddenException (403) if the caller is not a member.
     */
    public void requireMember(String roomId, String userId) {
        if (userId == null || userId.isBlank()) {
            throw new UnauthorizedException("인증이 필요합니다.");
        }
        if (roomMemberRepository.existsByRoomIdAndUserId(roomId, userId)) return;
        throw new ForbiddenException("방 멤버가 아닙니다.");
    }
}
