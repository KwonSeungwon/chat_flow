package com.chatflow.chat.service.room;

import com.chatflow.chat.entity.RoomMemberEntity;
import com.chatflow.chat.repository.RoomMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Shared room-membership predicate reused by both the STOMP send-path
 * ({@link com.chatflow.chat.controller.ChatController}) and the STOMP
 * subscribe-path ({@link com.chatflow.chat.config.StompAuthChannelInterceptor}).
 *
 * <p>A user is a member iff a {@code room_members} row exists for
 * (roomId, userId). {@code chat_rooms.created_by} is NOT a membership signal:
 * a creator who was kicked, banned or left must not keep access.
 */
@Component
@RequiredArgsConstructor
public class RoomMembershipChecker {

    private final RoomMemberRepository roomMemberRepository;

    /**
     * Returns {@code true} if the user is a member of the given room.
     * Lightweight check — uses {@code existsBy} (no entity hydration).
     * Preferred for paths that only need a boolean (typing, markRead, SUBSCRIBE).
     */
    public boolean isMember(String roomId, String userId) {
        if (roomId == null || userId == null || userId.isBlank()) return false;
        return roomMemberRepository.existsByRoomIdAndUserId(roomId, userId);
    }

    /**
     * Membership check that also returns the resolved {@link RoomMemberEntity}.
     * Used on the send path where the caller also needs member metadata (e.g. mutedUntil)
     * to avoid a second DB round-trip.
     *
     * @return present with the entity if a {@code room_members} row exists, else empty.
     */
    public Optional<MembershipResult> findMember(String roomId, String userId) {
        if (roomId == null || userId == null || userId.isBlank()) return Optional.empty();
        return roomMemberRepository.findByRoomIdAndUserId(roomId, userId).map(MembershipResult::new);
    }

    /** Resolved membership — always backed by a {@code room_members} row (never null). */
    public record MembershipResult(RoomMemberEntity entity) {}
}
