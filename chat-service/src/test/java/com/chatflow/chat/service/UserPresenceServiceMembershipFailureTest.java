package com.chatflow.chat.service;

import com.chatflow.chat.entity.RoomMemberEntity;
import com.chatflow.chat.repository.RoomMemberRepository;
import com.chatflow.common.dto.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Regression tests for UserPresenceService.registerParticipant exception handling.
 * Benign DataIntegrityViolationException must be swallowed; any other failure
 * must propagate so callers learn that Redis state has diverged from room_members.
 */
@ExtendWith(MockitoExtension.class)
class UserPresenceServiceMembershipFailureTest {

    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private ChatPersistenceService chatPersistenceService;
    @Mock private ChatRoomService chatRoomService;
    @Mock private ParticipantService participantService;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private RoomMemberRepository roomMemberRepository;
    @Mock private RoomBanService roomBanService;
    @Mock private SetOperations<String, String> setOperations;

    private UserPresenceService service;

    private static final String ROOM_ID = "room-1";
    private static final String USER_ID = "user-1";
    private static final String USERNAME = "alice";
    private static final String SESSION_ID = "session-1";

    @BeforeEach
    void setUp() {
        service = new UserPresenceService(
                messagingTemplate, chatPersistenceService, chatRoomService,
                participantService, redisTemplate, roomMemberRepository, roomBanService);
    }

    private ChatMessage joinMessage() {
        ChatMessage m = new ChatMessage();
        m.setChatRoomId(ROOM_ID);
        m.setUserId(USER_ID);
        m.setUsername(USERNAME);
        m.setType(ChatMessage.MessageType.JOIN);
        m.setContent("test");
        return m;
    }

    private void primeJoinPath() {
        when(roomBanService.isBanned(anyString(), anyString())).thenReturn(false);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members(anyString())).thenReturn(Set.of());
        when(participantService.isRoomFull(anyString())).thenReturn(false);
        when(roomMemberRepository.existsByRoomIdAndUserId(anyString(), anyString()))
                .thenReturn(false);
    }

    @Test
    void dataIntegrityViolation_isSwallowedAsBenignConcurrentInsert() {
        // Race: another session created the same (roomId, userId) row first.
        // Should NOT throw — just log and continue.
        primeJoinPath();
        when(roomMemberRepository.save(any(RoomMemberEntity.class)))
                .thenThrow(new DataIntegrityViolationException("dup"));

        // Must complete normally
        service.join(joinMessage(), SESSION_ID);

        // save() was attempted exactly once
        verify(roomMemberRepository, times(1)).save(any(RoomMemberEntity.class));
    }

    @Test
    void unexpectedDbFailure_propagatesInsteadOfBeingSwallowed() {
        // A genuine DB outage / unknown error should NOT be silently swallowed —
        // Redis state alone would diverge from room_members and downstream
        // membership checks (kick, role) would silently misbehave.
        primeJoinPath();
        when(roomMemberRepository.save(any(RoomMemberEntity.class)))
                .thenThrow(new IllegalStateException("DB down"));

        assertThrows(
                IllegalStateException.class,
                () -> service.join(joinMessage(), SESSION_ID),
                "registerParticipant must propagate non-DataIntegrityViolation exceptions");
    }

    @Test
    void successfulSave_doesNotThrowAndCallsRepositoryOnce() {
        // Sanity: happy path still works — save called once, no exception.
        primeJoinPath();
        when(roomMemberRepository.save(any(RoomMemberEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.join(joinMessage(), SESSION_ID);

        verify(roomMemberRepository, times(1)).save(any(RoomMemberEntity.class));
    }

    @Test
    void existingMember_skipsSaveEntirely() {
        // If existsByRoomIdAndUserId returns true, save must not be attempted.
        when(roomBanService.isBanned(anyString(), anyString())).thenReturn(false);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members(anyString())).thenReturn(Set.of());
        when(participantService.isRoomFull(anyString())).thenReturn(false);
        when(roomMemberRepository.existsByRoomIdAndUserId(anyString(), anyString()))
                .thenReturn(true);

        service.join(joinMessage(), SESSION_ID);

        verify(roomMemberRepository, never()).save(any(RoomMemberEntity.class));
    }
}
