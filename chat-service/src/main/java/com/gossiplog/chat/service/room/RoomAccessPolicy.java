package com.gossiplog.chat.service.room;

import com.gossiplog.chat.entity.ChatRoom;
import com.gossiplog.chat.entity.RoomType;
import com.gossiplog.chat.repository.ChatRoomRepository;
import com.gossiplog.chat.repository.RoomMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for "may this user enter / see this room?".
 *
 * <p>Every user-initiated path that creates a {@code room_members} row
 * (STOMP addUser, invite link, password verify, DM create) asks this policy
 * first. Room-type rules:
 * <ul>
 *   <li>DM ({@link RoomType#DIRECT}): only its two seeded participants.</li>
 *   <li>Private ({@code isPrivate}): members only; new members enter via invite.</li>
 *   <li>Password: listed, but entry requires a verified password (or an invite).</li>
 *   <li>Public, password-less, non-DM: anyone not banned may auto-join.</li>
 * </ul>
 * Existing members always pass the type rules (re-entry); bans always win.
 *
 * <p>Reads rooms from the repository directly (not {@code ChatRoomService})
 * because {@code ChatRoomService -> RoomMembershipService -> RoomAccessPolicy}
 * would otherwise form a bean cycle.
 */
@Component
@RequiredArgsConstructor
public class RoomAccessPolicy {

    private final ChatRoomRepository chatRoomRepository;
    private final RoomMemberRepository roomMemberRepository;
    private final RoomBanService roomBanService;
    private final ParticipantService participantService;

    /** Why a join was refused; JoinDecision.allowed() == (reason == null). */
    public enum Denial { ROOM_NOT_FOUND, BANNED, DM_NOT_PARTICIPANT, PRIVATE_REQUIRES_INVITE, PASSWORD_REQUIRED, ROOM_FULL }

    public record JoinDecision(Denial reason) {
        public boolean allowed() {
            return reason == null;
        }
    }

    public enum GrantPath { PASSWORD, INVITE, DM_CREATE }

    private static final JoinDecision ALLOWED = new JoinDecision(null);

    /** Implicit join (STOMP addUser, first visit): only public, password-less, non-DM rooms. Banned → BANNED. */
    public JoinDecision canAutoJoin(String roomId, String userId) {
        ChatRoom room = roomId != null ? chatRoomRepository.findById(roomId).orElse(null) : null;
        if (room == null) return deny(Denial.ROOM_NOT_FOUND);
        if (isBanned(roomId, userId)) return deny(Denial.BANNED);
        if (isMember(roomId, userId)) return ALLOWED;
        if (room.getRoomType() == RoomType.DIRECT) return deny(Denial.DM_NOT_PARTICIPANT);
        if (room.isPrivate()) return deny(Denial.PRIVATE_REQUIRES_INVITE);
        if (hasPassword(room)) return deny(Denial.PASSWORD_REQUIRED);
        return ALLOWED;
    }

    /** Explicit grant via a verified path. via = PASSWORD | INVITE | DM_CREATE. Always checks ban. */
    public JoinDecision canGrant(String roomId, String userId, GrantPath via) {
        ChatRoom room = roomId != null ? chatRoomRepository.findById(roomId).orElse(null) : null;
        if (room == null) return deny(Denial.ROOM_NOT_FOUND);
        if (isBanned(roomId, userId)) return deny(Denial.BANNED);
        boolean dm = room.getRoomType() == RoomType.DIRECT;
        return switch (via) {
            // DM_CREATE is only valid for the DM room DmRoomService just resolved, and
            // never seats a third user in a DM that already has its two participants.
            case DM_CREATE -> dm && (isMember(roomId, userId) || roomMemberRepository.countByRoomId(roomId) < 2)
                    ? ALLOWED : deny(Denial.DM_NOT_PARTICIPANT);
            // A password grant only means something on a room that has a password;
            // private rooms and DMs are never enterable by password.
            case PASSWORD -> {
                if (dm) yield deny(Denial.DM_NOT_PARTICIPANT);
                if (room.isPrivate()) yield deny(Denial.PRIVATE_REQUIRES_INVITE);
                if (!hasPassword(room)) yield deny(Denial.PASSWORD_REQUIRED);
                yield ALLOWED;
            }
            // Invite links bypass private/password, but never add a third party to a DM.
            case INVITE -> {
                if (dm) yield isMember(roomId, userId) ? ALLOWED : deny(Denial.DM_NOT_PARTICIPANT);
                if (isMember(roomId, userId)) yield ALLOWED;
                if (participantService.isRoomFull(roomId)) yield deny(Denial.ROOM_FULL);
                yield ALLOWED;
            }
        };
    }

    /** Visible in room list: non-DM non-private rooms, plus any room the user is a member of. */
    public boolean isVisible(ChatRoom room, String userId) {
        if (room == null) return false;
        if (room.getRoomType() != RoomType.DIRECT && !room.isPrivate()) return true;
        return isMember(room.getId(), userId);
    }

    private boolean isMember(String roomId, String userId) {
        return userId != null && !userId.isBlank()
                && roomMemberRepository.existsByRoomIdAndUserId(roomId, userId);
    }

    private boolean isBanned(String roomId, String userId) {
        return userId != null && !userId.isBlank() && roomBanService.isBanned(roomId, userId);
    }

    private static boolean hasPassword(ChatRoom room) {
        return room.getPassword() != null && !room.getPassword().isEmpty();
    }

    private static JoinDecision deny(Denial reason) {
        return new JoinDecision(reason);
    }
}
