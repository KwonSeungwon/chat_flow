package com.chatflow.chat.service;

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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Focused tests for the ban gate added to UserPresenceService.join().
 * Verifies banned users are rejected before any Redis/persistence logic runs.
 */
@ExtendWith(MockitoExtension.class)
class UserPresenceServiceBanGateTest {

    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private ChatPersistenceService chatPersistenceService;
    @Mock private ChatRoomService chatRoomService;
    @Mock private ParticipantService participantService;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private RoomMemberRepository roomMemberRepository;
    @Mock private RoomBanService roomBanService;
    @Mock private SetOperations<String, String> setOperations;

    private UserPresenceService userPresenceService;

    private static final String ROOM_ID = "room-1";
    private static final String USER_ID = "user-1";
    private static final String USERNAME = "testuser";
    private static final String SESSION_ID = "session-1";

    @BeforeEach
    void setUp() {
        userPresenceService = new UserPresenceService(
                messagingTemplate, chatPersistenceService, chatRoomService,
                participantService, redisTemplate, roomMemberRepository, roomBanService);
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

    // ── Banned user ────────────────────────────────────────────

    @Test
    void bannedUser_joinBlocked_emitsRoomBannedError() {
        when(roomBanService.isBanned(ROOM_ID, USER_ID)).thenReturn(true);

        ChatMessage message = createJoinMessage(USER_ID, USERNAME);
        userPresenceService.join(message, SESSION_ID);

        // Verify ROOM_BANNED error broadcast
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(messagingTemplate).convertAndSend(
                eq("/topic/chat/" + ROOM_ID + "/errors"),
                payloadCaptor.capture());

        Map<String, Object> payload = payloadCaptor.getValue();
        assertEquals("ROOM_BANNED", payload.get("type"));
        assertEquals(ROOM_ID, payload.get("roomId"));

        // Verify that NO subsequent join logic ran
        verify(redisTemplate, never()).opsForSet();
        verify(participantService, never()).isRoomFull(anyString());
        verify(participantService, never()).setParticipantCount(anyString(), anyInt());
        verify(chatPersistenceService, never()).saveOutboxEventAndPublish(any(), anyString(), anyString());
    }

    @Test
    void bannedUser_noPresenceBroadcast() {
        when(roomBanService.isBanned(ROOM_ID, USER_ID)).thenReturn(true);

        ChatMessage message = createJoinMessage(USER_ID, USERNAME);
        userPresenceService.join(message, SESSION_ID);

        // Verify no /presence broadcast
        verify(messagingTemplate, never()).convertAndSend(
                contains("/presence"), any(Object.class));
    }

    // ── Not banned user ────────────────────────────────────────

    @Test
    void notBannedUser_joinProceeds_redisSetAddCalled() {
        when(roomBanService.isBanned(ROOM_ID, USER_ID)).thenReturn(false);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members(anyString())).thenReturn(Set.of());
        when(participantService.isRoomFull(ROOM_ID)).thenReturn(false);

        ChatMessage message = createJoinMessage(USER_ID, USERNAME);
        userPresenceService.join(message, SESSION_ID);

        // Verify Redis SET add was called (join proceeded)
        String expectedKey = "chatflow:room:participants:" + ROOM_ID;
        String expectedEntry = USER_ID + ":" + SESSION_ID + ":" + USERNAME;
        verify(setOperations).add(expectedKey, expectedEntry);

        // Verify persistence was called
        verify(chatPersistenceService).saveOutboxEventAndPublish(
                any(ChatMessage.class), eq("chat-messages"), eq("USER_JOINED"));
    }

    // ── Anonymous user (empty userId) ──────────────────────────

    @Test
    void anonymousUser_banCheckSkipped_noBanServiceCall() {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members(anyString())).thenReturn(Set.of());
        when(participantService.isRoomFull(ROOM_ID)).thenReturn(false);

        ChatMessage message = createJoinMessage(null, USERNAME);
        userPresenceService.join(message, SESSION_ID);

        // Ban check should be skipped for null/empty userId
        verify(roomBanService, never()).isBanned(anyString(), anyString());

        // Join should still proceed
        verify(setOperations).add(anyString(), anyString());
    }

    @Test
    void emptyUserIdString_banCheckSkipped() {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members(anyString())).thenReturn(Set.of());
        when(participantService.isRoomFull(ROOM_ID)).thenReturn(false);

        ChatMessage message = createJoinMessage("", USERNAME);
        userPresenceService.join(message, SESSION_ID);

        verify(roomBanService, never()).isBanned(anyString(), anyString());
    }

    // ── Ban gate runs before full-room check ───────────────────

    @Test
    void bannedUser_fullRoomCheckNeverReached() {
        when(roomBanService.isBanned(ROOM_ID, USER_ID)).thenReturn(true);

        ChatMessage message = createJoinMessage(USER_ID, USERNAME);
        userPresenceService.join(message, SESSION_ID);

        // Full-room check should never be reached
        verify(participantService, never()).isRoomFull(anyString());
    }
}
