package com.chatflow.chat.service;

import com.chatflow.chat.service.presence.BanCheckService;
import com.chatflow.chat.service.presence.ParticipantRegistryService;
import com.chatflow.chat.service.presence.PresenceBroadcastService;
import com.chatflow.chat.service.presence.RoomFullnessService;
import com.chatflow.chat.service.room.RoomAccessPolicy;
import com.chatflow.common.dto.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Orchestrator-level tests for room-fullness delegation in
 * UserPresenceService.join(). The actual full-room logic (DM vs. general,
 * redirect, error broadcasts) is tested in RoomFullnessServiceTest -- here
 * we verify the orchestrator respects the handleIfFull return value.
 */
@ExtendWith(MockitoExtension.class)
class UserPresenceServiceRoomFullTest {

    @Mock private BanCheckService banCheckService;
    @Mock private RoomFullnessService roomFullnessService;
    @Mock private ParticipantRegistryService participantRegistry;
    @Mock private PresenceBroadcastService presenceBroadcast;
    @Mock private RoomAccessPolicy roomAccessPolicy;

    private UserPresenceService userPresenceService;

    private static final String ROOM_ID = "room-full-1";
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

    private ChatMessage createJoinMessage() {
        ChatMessage msg = new ChatMessage();
        msg.setChatRoomId(ROOM_ID);
        msg.setUserId(USER_ID);
        msg.setUsername(USERNAME);
        msg.setType(ChatMessage.MessageType.JOIN);
        msg.setContent("test");
        return msg;
    }

    private void stubBanPassAndEmptyRoom() {
        when(banCheckService.checkBanGate(USER_ID, ROOM_ID, USERNAME)).thenReturn(false);
        when(participantRegistry.getRoomParticipantUserIds(ROOM_ID)).thenReturn(Set.of());
    }

    // -- Room not full --

    @Test
    void roomNotFull_joinProceeds() {
        stubBanPassAndEmptyRoom();
        when(roomFullnessService.handleIfFull(any(ChatMessage.class), eq(USER_ID), eq(false)))
                .thenReturn(false);

        ChatMessage message = createJoinMessage();
        userPresenceService.join(message, SESSION_ID);

        verify(participantRegistry).register(message, SESSION_ID);
    }

    // -- DM full, non-member: handleIfFull returns true --

    @Test
    void dmFull_nonMember_joinAborted() {
        stubBanPassAndEmptyRoom();
        when(roomFullnessService.handleIfFull(any(ChatMessage.class), eq(USER_ID), eq(false)))
                .thenReturn(true);

        ChatMessage message = createJoinMessage();
        userPresenceService.join(message, SESSION_ID);

        verifyNoInteractions(presenceBroadcast);
        verify(participantRegistry, never()).register(any(), any());
    }

    // -- DM full, existing member: handleIfFull returns false --

    @Test
    void dmFull_existingMember_joinAllowed() {
        stubBanPassAndEmptyRoom();
        when(roomFullnessService.handleIfFull(any(ChatMessage.class), eq(USER_ID), eq(false)))
                .thenReturn(false);

        ChatMessage message = createJoinMessage();
        userPresenceService.join(message, SESSION_ID);

        verify(participantRegistry).register(message, SESSION_ID);
    }

    // -- Non-DM full: handleIfFull returns false but mutates chatRoomId --

    @Test
    void nonDmFull_redirectToNewRoom_chatRoomIdMutated() {
        stubBanPassAndEmptyRoom();

        String newRoomId = "room-full-2";
        doAnswer(invocation -> {
            ChatMessage msg = invocation.getArgument(0);
            msg.setChatRoomId(newRoomId);
            return false;
        }).when(roomFullnessService).handleIfFull(any(ChatMessage.class), eq(USER_ID), eq(false));

        // After redirect, getRoomParticipantUserIds is called again with the new room ID
        when(participantRegistry.getRoomParticipantUserIds(newRoomId)).thenReturn(Set.of(USER_ID));

        ChatMessage message = createJoinMessage();
        userPresenceService.join(message, SESSION_ID);

        // chatRoomId was mutated by handleIfFull
        assertEquals(newRoomId, message.getChatRoomId());

        // Register and broadcast proceed with the mutated message
        verify(participantRegistry).register(message, SESSION_ID);
        verify(presenceBroadcast).broadcastJoin(message, 1);
    }

    // -- Full private room, non-member: refused before the fullness gate --

    @Test
    void nonMember_joinsFullPrivateRoom_refused_noRedirect_noSiblingRoom() {
        when(banCheckService.checkBanGate(USER_ID, ROOM_ID, USERNAME)).thenReturn(false);
        when(roomAccessPolicy.canAutoJoin(ROOM_ID, USER_ID)).thenReturn(
                new RoomAccessPolicy.JoinDecision(RoomAccessPolicy.Denial.PRIVATE_REQUIRES_INVITE));

        ChatMessage message = createJoinMessage();
        boolean joined = userPresenceService.join(message, SESSION_ID);

        assertFalse(joined);
        // handleIfFull is what would create/redirect into a sibling room — never reached.
        verifyNoInteractions(roomFullnessService);
        assertEquals(ROOM_ID, message.getChatRoomId());
        verify(participantRegistry, never()).register(any(), any());
        verify(presenceBroadcast).sendJoinRefused(
                USER_ID, ROOM_ID, RoomAccessPolicy.Denial.PRIVATE_REQUIRES_INVITE);
        verify(presenceBroadcast, never()).broadcastJoin(any(), anyInt());
    }
}
