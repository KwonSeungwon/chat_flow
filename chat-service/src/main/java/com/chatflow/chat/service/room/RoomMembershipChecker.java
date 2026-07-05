package com.chatflow.chat.service.room;

import com.chatflow.chat.repository.ChatRoomRepository;
import com.chatflow.chat.repository.RoomMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

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
     */
    public boolean isMember(String roomId, String userId) {
        if (roomId == null || userId == null || userId.isBlank()) return false;
        if (roomMemberRepository.existsByRoomIdAndUserId(roomId, userId)) return true;
        return chatRoomRepository.findById(roomId)
                .map(r -> userId.equals(r.getCreatedBy()))
                .orElse(false);
    }
}
