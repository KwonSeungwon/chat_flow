package com.gossiplog.chat.service;

import com.gossiplog.chat.service.presence.BanCheckService;
import com.gossiplog.chat.service.presence.ParticipantRegistryService;
import com.gossiplog.chat.service.presence.PresenceBroadcastService;
import com.gossiplog.chat.service.presence.RoomFullnessService;
import com.gossiplog.chat.service.room.RoomAccessPolicy;
import com.gossiplog.common.dto.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Orchestrator-level tests for the ban gate in UserPresenceService.join().
 * Verifies delegation order: banned users trigger early return with no
 * further collaborator calls.
 */
@ExtendWith(MockitoExtension.class)
class UserPresenceServiceBanGateTest {

    @Mock private BanCheckService banCheckService;
    @Mock private RoomFullnessService roomFullnessService;
    @Mock private ParticipantRegistryService participantRegistry;
    @Mock private PresenceBroadcastService presenceBroadcast;
    @Mock private RoomAccessPolicy roomAccessPolicy;

    private UserPresenceService userPresenceService;

    private static final String ROOM_ID = "room-1";
    private static final String USER_ID = "user-1";
    private static final String USERNAME = "testuser";
    private static final String SESSION_ID = "session-1";

    @BeforeEach
    void setUp() {
        userPresenceService = new UserPresenceService(
                banCheckService, roomFullnessService, participantRegistry, presenceBroadcast,
                roomAccessPolicy);
        // Default: the access policy (pre-fullness gate and inside register) accepts the join.
        lenient().when(roomAccessPolicy.canAutoJoin(any(), any()))
                .thenReturn(new RoomAccessPolicy.JoinDecision(null));
        lenient().when(participantRegistry.register(any(), any()))
                .thenReturn(new RoomAccessPolicy.JoinDecision(null));
    }

    private ChatMessage createJoinMessage(String userId, String username) {
        ChatMessage msg = new ChatMessage();
        msg.setChatRoomId(ROOM_ID);
        msg.setUserId(userId);
        msg.setUsername(username);
        msg.setType(ChatMessage.MessageType.JOIN);
        msg.setContent("test");
        return msg;
    }

    // -- Banned user --

    @Test
    void bannedUser_joinAborted_noFurtherDelegation() {
        when(banCheckService.checkBanGate(USER_ID, ROOM_ID, USERNAME)).thenReturn(true);

        ChatMessage message = createJoinMessage(USER_ID, USERNAME);
        userPresenceService.join(message, SESSION_ID);

        verify(banCheckService).checkBanGate(USER_ID, ROOM_ID, USERNAME);
        verifyNoInteractions(roomFullnessService);
        verifyNoInteractions(participantRegistry);
        verifyNoInteractions(presenceBroadcast);
    }

    @Test
    void bannedUser_fullRoomCheckNeverReached() {
        when(banCheckService.checkBanGate(USER_ID, ROOM_ID, USERNAME)).thenReturn(true);

        ChatMessage message = createJoinMessage(USER_ID, USERNAME);
        userPresenceService.join(message, SESSION_ID);

        verifyNoInteractions(roomFullnessService);
    }

    // -- Not banned user --

    @Test
    void notBannedUser_joinProceeds_callsRegisterAndBroadcast() {
        when(banCheckService.checkBanGate(USER_ID, ROOM_ID, USERNAME)).thenReturn(false);
        when(participantRegistry.getRoomParticipantUserIds(ROOM_ID))
                .thenReturn(Set.of())              // first call: determine alreadyJoined
                .thenReturn(Set.of(USER_ID));       // second call: participantCount after register
        when(roomFullnessService.handleIfFull(any(ChatMessage.class), eq(USER_ID), eq(false)))
                .thenReturn(false);

        ChatMessage message = createJoinMessage(USER_ID, USERNAME);
        userPresenceService.join(message, SESSION_ID);

        verify(participantRegistry).register(message, SESSION_ID);
        verify(presenceBroadcast).broadcastJoin(message, 1);
    }

    // -- Access policy refusal --

    @Test
    void policyRefusal_noJoinBroadcast_userGetsRefusalFrame() {
        when(banCheckService.checkBanGate(USER_ID, ROOM_ID, USERNAME)).thenReturn(false);
        when(participantRegistry.getRoomParticipantUserIds(ROOM_ID)).thenReturn(Set.of());
        when(roomFullnessService.handleIfFull(any(ChatMessage.class), eq(USER_ID), eq(false)))
                .thenReturn(false);
        when(participantRegistry.register(any(), any())).thenReturn(
                new RoomAccessPolicy.JoinDecision(RoomAccessPolicy.Denial.DM_NOT_PARTICIPANT));

        ChatMessage message = createJoinMessage(USER_ID, USERNAME);
        boolean joined = userPresenceService.join(message, SESSION_ID);

        org.junit.jupiter.api.Assertions.assertFalse(joined);
        verify(presenceBroadcast).sendJoinRefused(USER_ID, ROOM_ID, RoomAccessPolicy.Denial.DM_NOT_PARTICIPANT);
        verify(presenceBroadcast, never()).broadcastJoin(any(), anyInt());
    }

    // -- Anonymous user (null/empty userId) --

    @Test
    void anonymousUser_banCheckCalledWithEmptyString() {
        when(banCheckService.checkBanGate(eq(""), eq(ROOM_ID), eq(USERNAME))).thenReturn(false);
        when(participantRegistry.getRoomParticipantUserIds(ROOM_ID))
                .thenReturn(Set.of())
                .thenReturn(Set.of());
        when(roomFullnessService.handleIfFull(any(ChatMessage.class), eq(""), eq(false)))
                .thenReturn(false);

        ChatMessage message = createJoinMessage(null, USERNAME);
        userPresenceService.join(message, SESSION_ID);

        verify(banCheckService).checkBanGate("", ROOM_ID, USERNAME);
        verify(participantRegistry).register(message, SESSION_ID);
    }

    @Test
    void emptyUserIdString_banCheckCalledWithEmptyString() {
        when(banCheckService.checkBanGate(eq(""), eq(ROOM_ID), eq(USERNAME))).thenReturn(false);
        when(participantRegistry.getRoomParticipantUserIds(ROOM_ID))
                .thenReturn(Set.of())
                .thenReturn(Set.of());
        when(roomFullnessService.handleIfFull(any(ChatMessage.class), eq(""), eq(false)))
                .thenReturn(false);

        ChatMessage message = createJoinMessage("", USERNAME);
        userPresenceService.join(message, SESSION_ID);

        verify(banCheckService).checkBanGate("", ROOM_ID, USERNAME);
    }
}
