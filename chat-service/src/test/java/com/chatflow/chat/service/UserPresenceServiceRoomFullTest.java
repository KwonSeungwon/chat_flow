package com.chatflow.chat.service;

import com.chatflow.chat.entity.ChatRoom;
import com.chatflow.chat.entity.RoomType;
import com.chatflow.chat.repository.RoomMemberRepository;
import com.chatflow.common.dto.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Focused tests for handleRoomFullIfNeeded branches in UserPresenceService.join().
 * Covers: DM full + non-member, DM full + existing member, non-DM redirect, not full.
 */
@ExtendWith(MockitoExtension.class)
class UserPresenceServiceRoomFullTest {

    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private ChatPersistenceService chatPersistenceService;
    @Mock private ChatRoomService chatRoomService;
    @Mock private ParticipantService participantService;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private RoomMemberRepository roomMemberRepository;
    @Mock private RoomBanService roomBanService;
    @Mock private SetOperations<String, String> setOperations;

    private UserPresenceService userPresenceService;

    private static final String ROOM_ID = "room-full-1";
    private static final String USER_ID = "user-1";
    private static final String USERNAME = "testuser";
    private static final String SESSION_ID = "session-1";

    @BeforeEach
    void setUp() {
        userPresenceService = new UserPresenceService(
                messagingTemplate, chatPersistenceService, chatRoomService,
                participantService, redisTemplate, roomMemberRepository, roomBanService);
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

    /**
     * Stub ban check (pass) and Redis members (empty = new user).
     */
    private void stubBanPassAndEmptyRoom() {
        when(roomBanService.isBanned(ROOM_ID, USER_ID)).thenReturn(false);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members("chatflow:room:participants:" + ROOM_ID)).thenReturn(Set.of());
    }

    // ── Room not full → returns false immediately ──────────────

    @Test
    void roomNotFull_joinProceeds() {
        stubBanPassAndEmptyRoom();
        when(participantService.isRoomFull(ROOM_ID)).thenReturn(false);

        ChatMessage message = createJoinMessage();
        userPresenceService.join(message, SESSION_ID);

        // Room not full: no error broadcast, no chatRoomService.getRoom call
        verify(chatRoomService, never()).getRoom(anyString());
        // Join proceeds: Redis add is called
        verify(setOperations).add(anyString(), anyString());
    }

    // ── DM full + non-member → ROOM_FULL_DM error, join aborted ──

    @Test
    void dmFull_nonMember_joinAborted_emitsRoomFullDmError() {
        stubBanPassAndEmptyRoom();
        when(participantService.isRoomFull(ROOM_ID)).thenReturn(true);

        ChatRoom dmRoom = ChatRoom.builder()
                .id(ROOM_ID)
                .name("DM Room")
                .roomType(RoomType.DIRECT)
                .build();
        when(chatRoomService.getRoom(ROOM_ID)).thenReturn(Optional.of(dmRoom));
        when(roomMemberRepository.existsByRoomIdAndUserId(ROOM_ID, USER_ID)).thenReturn(false);

        ChatMessage message = createJoinMessage();
        userPresenceService.join(message, SESSION_ID);

        // Verify ROOM_FULL_DM error broadcast
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(messagingTemplate).convertAndSend(
                eq("/topic/chat/" + ROOM_ID + "/errors"),
                payloadCaptor.capture());

        Map<String, Object> payload = payloadCaptor.getValue();
        assertEquals("ROOM_FULL_DM", payload.get("type"));
        assertEquals(ROOM_ID, payload.get("roomId"));
        assertEquals("DM Room", payload.get("roomName"));

        // Join was aborted: no Redis SET add (registerParticipant not reached), no persistence
        verify(setOperations, never()).add(anyString(), anyString());
        verify(chatPersistenceService, never()).saveOutboxEventAndPublish(any(), anyString(), anyString());
    }

    // ── DM full + existing member → join allowed ──────────────

    @Test
    void dmFull_existingMember_joinAllowed() {
        stubBanPassAndEmptyRoom();
        when(participantService.isRoomFull(ROOM_ID)).thenReturn(true);

        ChatRoom dmRoom = ChatRoom.builder()
                .id(ROOM_ID)
                .name("DM Room")
                .roomType(RoomType.DIRECT)
                .build();
        when(chatRoomService.getRoom(ROOM_ID)).thenReturn(Optional.of(dmRoom));
        when(roomMemberRepository.existsByRoomIdAndUserId(ROOM_ID, USER_ID)).thenReturn(true);

        ChatMessage message = createJoinMessage();
        userPresenceService.join(message, SESSION_ID);

        // No error broadcast to /errors
        verify(messagingTemplate, never()).convertAndSend(
                eq("/topic/chat/" + ROOM_ID + "/errors"), any(Object.class));

        // Join proceeds: Redis add called (via registerParticipant)
        verify(setOperations).add(anyString(), anyString());
    }

    // ── Non-DM full → redirect to new room, chatRoomId mutated ──

    @Test
    void nonDmFull_redirectToNewRoom_chatRoomIdMutated() {
        stubBanPassAndEmptyRoom();
        when(participantService.isRoomFull(ROOM_ID)).thenReturn(true);

        ChatRoom generalRoom = ChatRoom.builder()
                .id(ROOM_ID)
                .name("General-1")
                .roomType(RoomType.GENERAL)
                .build();
        when(chatRoomService.getRoom(ROOM_ID)).thenReturn(Optional.of(generalRoom));

        String newRoomId = "room-full-2";
        ChatRoom newRoom = ChatRoom.builder()
                .id(newRoomId)
                .name("General-2")
                .roomType(RoomType.GENERAL)
                .build();
        when(participantService.findOrCreateAvailableRoom("General")).thenReturn(newRoom);

        // Need to stub setOperations for the new room too (registerParticipant uses the mutated chatRoomId)
        when(setOperations.members("chatflow:room:participants:" + newRoomId)).thenReturn(Set.of());

        ChatMessage message = createJoinMessage();
        userPresenceService.join(message, SESSION_ID);

        // Verify ROOM_FULL error with redirect info
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(messagingTemplate).convertAndSend(
                eq("/topic/chat/" + ROOM_ID + "/errors"),
                payloadCaptor.capture());

        Map<String, Object> payload = payloadCaptor.getValue();
        assertEquals("ROOM_FULL", payload.get("type"));
        assertEquals(newRoomId, payload.get("redirectTo"));
        assertEquals("General-2", payload.get("roomName"));

        // Side effect: message.chatRoomId was mutated to the new room
        assertEquals(newRoomId, message.getChatRoomId());

        // Join proceeds on the NEW room (registerParticipant uses mutated chatRoomId)
        String expectedKey = "chatflow:room:participants:" + newRoomId;
        verify(setOperations).add(eq(expectedKey), anyString());
    }
}
