package com.chatflow.chat.service.room;

import com.chatflow.chat.entity.RoomMemberEntity;
import com.chatflow.chat.repository.ChatRoomRepository;
import com.chatflow.chat.repository.RoomMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Shared room-membership predicate reused by both the STOMP send-path
 * ({@link com.chatflow.chat.controller.ChatController}) and the STOMP
 * subscribe-path ({@link com.chatflow.chat.config.StompAuthChannelInterceptor}).
 *
 * <p>A user is considered a member if:
 * <ol>
 *   <li>A {@code room_members} row exists for (roomId, userId), OR</li>
 *   <li>The user is the room creator (legacy bridge for pre-seed rooms).</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class RoomMembershipChecker {

    private final RoomMemberRepository roomMemberRepository;
    private final ChatRoomRepository chatRoomRepository;

    /**
     * Returns {@code true} if the user is a member of the given room.
     * Lightweight check — uses {@code existsBy} (no entity hydration).
     * Preferred for paths that only need a boolean (typing, markRead, SUBSCRIBE).
     */
    public boolean isMember(String roomId, String userId) {
        if (roomId == null || userId == null || userId.isBlank()) return false;
        if (roomMemberRepository.existsByRoomIdAndUserId(roomId, userId)) return true;
        return isCreator(roomId, userId);
    }

    /**
     * Membership check that also returns the resolved {@link RoomMemberEntity}.
     * Used on the send path where the caller also needs member metadata (e.g. mutedUntil)
     * to avoid a second DB round-trip.
     *
     * @return present with the entity if a {@code room_members} row exists,
     *         present with {@link MembershipResult#creatorOnly() creatorOnly}=true
     *         if the user is the room creator (legacy bridge, no row),
     *         or empty if the user is not a member at all.
     */
    public Optional<MembershipResult> findMember(String roomId, String userId) {
        if (roomId == null || userId == null || userId.isBlank()) return Optional.empty();
        Optional<RoomMemberEntity> member = roomMemberRepository.findByRoomIdAndUserId(roomId, userId);
        if (member.isPresent()) return Optional.of(new MembershipResult(member.get(), false));
        if (isCreator(roomId, userId)) return Optional.of(MembershipResult.CREATOR_ONLY);
        return Optional.empty();
    }

    private boolean isCreator(String roomId, String userId) {
        return chatRoomRepository.findById(roomId)
                .map(r -> userId.equals(r.getCreatedBy()))
                .orElse(false);
    }

    /**
     * Resolved membership. When the user is a member via the room_members table,
     * {@link #entity()} returns the row and {@link #creatorOnly()} is false.
     * When membership is via the legacy creator fallback (no row), entity is
     * {@code null} and {@link #creatorOnly()} is true — callers should check
     * {@code creatorOnly()} rather than testing entity for null.
     */
    public record MembershipResult(RoomMemberEntity entity, boolean creatorOnly) {
        /** Sentinel for legacy creator-only membership (no room_members row). */
        public static final MembershipResult CREATOR_ONLY = new MembershipResult(null, true);
    }
}
