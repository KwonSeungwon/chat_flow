package com.chatflow.chat.service;

import com.chatflow.chat.service.presence.BanCheckService;
import com.chatflow.chat.service.presence.ParticipantRegistryService;
import com.chatflow.chat.service.presence.PresenceBroadcastService;
import com.chatflow.chat.service.presence.RoomFullnessService;
import com.chatflow.chat.service.room.RoomAccessPolicy;
import com.chatflow.common.dto.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Orchestrates a user's join/leave lifecycle for a chat room. Delegates
 * gate checks (ban, room-full), registry side effects (Redis SET +
 * policy-gated room_members auto-join), and STOMP broadcast to focused collaborators.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserPresenceService {

    private final BanCheckService banCheckService;
    private final RoomFullnessService roomFullnessService;
    private final ParticipantRegistryService participantRegistry;
    private final PresenceBroadcastService presenceBroadcast;
    private final RoomAccessPolicy roomAccessPolicy;

    /**
     * @return true if the user joined (presence registered); false if the join
     *         was refused (banned, DM full, or denied by RoomAccessPolicy). The
     *         refused user has already been sent a {@code /user/queue/errors} frame.
     */
    public boolean join(ChatMessage message, String sessionId) {
        String currentUserId = message.getUserId() != null ? message.getUserId() : "";

        if (banCheckService.checkBanGate(currentUserId, message.getChatRoomId(), message.getUsername())) {
            return false;
        }

        // Access gate on the ORIGINAL room, before the fullness gate: a non-member
        // must not be redirected into an overflow sibling of a private/password/DM
        // room (the redirect would both leak the room and spawn a public copy).
        RoomAccessPolicy.JoinDecision original = roomAccessPolicy.canAutoJoin(
                message.getChatRoomId(), currentUserId.isEmpty() ? null : currentUserId);
        if (!original.allowed()) {
            presenceBroadcast.sendJoinRefused(currentUserId, message.getChatRoomId(), original.reason());
            return false;
        }

        Set<String> existingUserIds = participantRegistry.getRoomParticipantUserIds(message.getChatRoomId());
        boolean alreadyJoined = !currentUserId.isEmpty() && existingUserIds.contains(currentUserId);

        if (roomFullnessService.handleIfFull(message, currentUserId, alreadyJoined)) {
            return false;
        }

        // register re-checks RoomAccessPolicy (the fullness gate may have redirected
        // the message to another room); a refused join must not broadcast JOIN.
        RoomAccessPolicy.JoinDecision decision = participantRegistry.register(message, sessionId);
        if (!decision.allowed()) {
            presenceBroadcast.sendJoinRefused(currentUserId, message.getChatRoomId(), decision.reason());
            return false;
        }

        if (alreadyJoined) {
            log.debug("User {} reconnected to room {} via additional session — suppressing JOIN broadcast",
                    message.getUsername(), message.getChatRoomId());
            return true;
        }

        int participantCount = participantRegistry.getRoomParticipantUserIds(message.getChatRoomId()).size();
        presenceBroadcast.broadcastJoin(message, participantCount);
        return true;
    }

    public void leave(String roomId, String username, String sessionId) {
        participantRegistry.removeSession(roomId, username, sessionId);

        boolean userStillPresent = participantRegistry.userStillPresent(roomId, username);

        if (!userStillPresent) {
            presenceBroadcast.persistLeaveEvent(roomId, username);
        } else {
            log.info("User {} closed a tab in room {} (still has active sessions)", username, roomId);
        }

        participantRegistry.syncParticipantCount(roomId);

        if (!userStillPresent) {
            int participantCount = participantRegistry.getRoomParticipantUserIds(roomId).size();
            presenceBroadcast.broadcastLeave(roomId, username, participantCount);
        }
    }

    public Set<String> getRoomParticipantUserIds(String roomId) {
        return participantRegistry.getRoomParticipantUserIds(roomId);
    }
}
