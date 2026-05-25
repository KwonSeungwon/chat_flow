package com.chatflow.chat.service;

import com.chatflow.chat.service.presence.BanCheckService;
import com.chatflow.chat.service.presence.ParticipantRegistryService;
import com.chatflow.chat.service.presence.PresenceBroadcastService;
import com.chatflow.chat.service.presence.RoomFullnessService;
import com.chatflow.common.dto.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Orchestrates a user's join/leave lifecycle for a chat room. Delegates
 * gate checks (ban, room-full), registry side effects (Redis SET +
 * room_members backfill), and STOMP broadcast to focused collaborators.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserPresenceService {

    private final BanCheckService banCheckService;
    private final RoomFullnessService roomFullnessService;
    private final ParticipantRegistryService participantRegistry;
    private final PresenceBroadcastService presenceBroadcast;

    public void join(ChatMessage message, String sessionId) {
        String currentUserId = message.getUserId() != null ? message.getUserId() : "";

        if (banCheckService.checkBanGate(currentUserId, message.getChatRoomId(), message.getUsername())) {
            return;
        }

        Set<String> existingUserIds = participantRegistry.getRoomParticipantUserIds(message.getChatRoomId());
        boolean alreadyJoined = !currentUserId.isEmpty() && existingUserIds.contains(currentUserId);

        if (roomFullnessService.handleIfFull(message, currentUserId, alreadyJoined)) {
            return;
        }

        participantRegistry.register(message, sessionId);

        if (alreadyJoined) {
            log.debug("User {} reconnected to room {} via additional session — suppressing JOIN broadcast",
                    message.getUsername(), message.getChatRoomId());
            return;
        }

        int participantCount = participantRegistry.getRoomParticipantUserIds(message.getChatRoomId()).size();
        presenceBroadcast.broadcastJoin(message, participantCount);
    }

    public void join(ChatMessage message) {
        join(message, null);
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

    public void leave(String roomId, String username) {
        leave(roomId, username, null);
    }

    public Set<String> getRoomParticipantUserIds(String roomId) {
        return participantRegistry.getRoomParticipantUserIds(roomId);
    }
}
