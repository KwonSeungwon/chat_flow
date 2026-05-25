package com.chatflow.chat.service;

import com.chatflow.chat.config.RedisHealthTracker;
import com.chatflow.chat.entity.RoomMemberEntity;
import com.chatflow.chat.entity.RoomRole;
import com.chatflow.chat.repository.RoomMemberRepository;
import com.chatflow.chat.result.ChatErrorCode;
import com.chatflow.chat.result.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RoomMembershipService covering member seeding,
 * leave-room lifecycle, and invite-message broadcasting.
 */
@ExtendWith(MockitoExtension.class)
class RoomMembershipServiceTest {

    @Mock private RoomMemberRepository roomMemberRepository;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private RedisHealthTracker redisHealth;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private ParticipantService participantService;
    @Mock private RoomCacheEvictor roomCacheEvictor;
    @Mock private SetOperations<String, String> setOps;

    private RoomMembershipService roomMembershipService;

    private static final String ROOM_ID = "room-1";
    private static final String USER_ID = "user-1";
    private static final String USERNAME = "alice";
    private static final String OTHER_USER_ID = "other-user";

    private final String participantKey = "chatflow:room:participants:" + ROOM_ID;

    @BeforeEach
    void setUp() {
        roomMembershipService = new RoomMembershipService(
                roomMemberRepository, redisTemplate, redisHealth,
                messagingTemplate, participantService, roomCacheEvictor);
    }

    // ── AddMemberIfAbsent ─────────────────────────────────────────

    @Nested
    class AddMemberIfAbsent {

        @Test
        void inserts_member_with_default_role_MEMBER() {
            when(roomMemberRepository.existsByRoomIdAndUserId(ROOM_ID, USER_ID))
                    .thenReturn(false);

            roomMembershipService.addMemberIfAbsent(ROOM_ID, USER_ID, USERNAME);

            ArgumentCaptor<RoomMemberEntity> captor = ArgumentCaptor.forClass(RoomMemberEntity.class);
            verify(roomMemberRepository).save(captor.capture());
            assertEquals(RoomRole.MEMBER, captor.getValue().getRole());
        }

        @Test
        void inserts_with_OWNER_when_4_arg_overload_called() {
            when(roomMemberRepository.existsByRoomIdAndUserId(ROOM_ID, USER_ID))
                    .thenReturn(false);

            roomMembershipService.addMemberIfAbsent(ROOM_ID, USER_ID, USERNAME, RoomRole.OWNER);

            ArgumentCaptor<RoomMemberEntity> captor = ArgumentCaptor.forClass(RoomMemberEntity.class);
            verify(roomMemberRepository).save(captor.capture());
            assertEquals(RoomRole.OWNER, captor.getValue().getRole());
        }

        @Test
        void noop_when_already_exists() {
            when(roomMemberRepository.existsByRoomIdAndUserId(ROOM_ID, USER_ID))
                    .thenReturn(true);

            roomMembershipService.addMemberIfAbsent(ROOM_ID, USER_ID, USERNAME);

            verify(roomMemberRepository, never()).save(any());
        }

        @Test
        void noop_on_blank_userId() {
            roomMembershipService.addMemberIfAbsent(ROOM_ID, "", USERNAME);
            roomMembershipService.addMemberIfAbsent(ROOM_ID, null, USERNAME);

            verifyNoInteractions(roomMemberRepository);
        }

        @Test
        void tolerates_concurrent_insert_race() {
            when(roomMemberRepository.existsByRoomIdAndUserId(ROOM_ID, USER_ID))
                    .thenReturn(false);
            when(roomMemberRepository.save(any(RoomMemberEntity.class)))
                    .thenThrow(new DataIntegrityViolationException("duplicate key"));

            assertDoesNotThrow(() ->
                    roomMembershipService.addMemberIfAbsent(ROOM_ID, USER_ID, USERNAME));
        }
    }

    // ── LeaveRoom ─────────────────────────────────────────────────

    @Nested
    class LeaveRoom {

        @Test
        void removes_userId_prefixed_entries_from_redis_set_and_syncs_count() {
            when(roomMemberRepository.existsByRoomIdAndUserId(ROOM_ID, USER_ID)).thenReturn(true);
            when(redisHealth.isCircuitOpen()).thenReturn(false);
            when(redisTemplate.opsForSet()).thenReturn(setOps);
            when(setOps.members(participantKey)).thenReturn(Set.of(
                    USER_ID + ":sess-a:Alice",
                    USER_ID + ":sess-b:Alice",
                    OTHER_USER_ID + ":sess-c:Bob"
            ));

            Result<Void, ChatErrorCode> result = roomMembershipService.leaveRoom(ROOM_ID, USER_ID, USERNAME);

            assertTrue(result.isSuccess());
            verify(setOps).remove(participantKey, USER_ID + ":sess-a:Alice");
            verify(setOps).remove(participantKey, USER_ID + ":sess-b:Alice");
            verify(setOps, never()).remove(eq(participantKey), eq(OTHER_USER_ID + ":sess-c:Bob"));

            verify(participantService).syncParticipantCountFromRedis(ROOM_ID);
            verify(roomCacheEvictor).evict(ROOM_ID);
        }

        @SuppressWarnings("unchecked")
        @Test
        void broadcasts_LEAVE_system_message() {
            when(roomMemberRepository.existsByRoomIdAndUserId(ROOM_ID, USER_ID)).thenReturn(true);
            when(redisHealth.isCircuitOpen()).thenReturn(false);
            when(redisTemplate.opsForSet()).thenReturn(setOps);
            when(setOps.members(participantKey)).thenReturn(Set.of());

            Result<Void, ChatErrorCode> result = roomMembershipService.leaveRoom(ROOM_ID, USER_ID, USERNAME);

            assertTrue(result.isSuccess());
            ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
            verify(messagingTemplate).convertAndSend(
                    eq("/topic/chat/" + ROOM_ID), payloadCaptor.capture());

            Map<String, Object> payload = payloadCaptor.getValue();
            assertEquals("LEAVE", payload.get("type"));
            assertEquals(ROOM_ID, payload.get("chatRoomId"));
            assertEquals(USERNAME, payload.get("username"));
            assertTrue(payload.get("content").toString().endsWith("채팅방을 나갔습니다."));
        }

        @Test
        void skips_redis_cleanup_when_circuit_open() {
            when(roomMemberRepository.existsByRoomIdAndUserId(ROOM_ID, USER_ID)).thenReturn(true);
            when(redisHealth.isCircuitOpen()).thenReturn(true);

            Result<Void, ChatErrorCode> result = roomMembershipService.leaveRoom(ROOM_ID, USER_ID, USERNAME);

            assertTrue(result.isSuccess());
            verify(redisTemplate, never()).opsForSet();

            // broadcast and count sync STILL happen
            verify(messagingTemplate).convertAndSend(eq("/topic/chat/" + ROOM_ID), any(Map.class));
            verify(participantService).syncParticipantCountFromRedis(ROOM_ID);
        }

        @Test
        void returns_NOT_FOUND_when_user_is_not_a_member() {
            // existsByRoomIdAndUserId defaults to false (not stubbed to true)

            Result<Void, ChatErrorCode> result = roomMembershipService.leaveRoom(ROOM_ID, USER_ID, USERNAME);

            assertTrue(result.isFailure());
            assertEquals(ChatErrorCode.NOT_FOUND, result.error());

            // No Redis cleanup, no broadcast, no sync
            verify(redisTemplate, never()).opsForSet();
            verify(messagingTemplate, never()).convertAndSend(anyString(), any(Map.class));
            verify(participantService, never()).syncParticipantCountFromRedis(anyString());
            verify(roomCacheEvictor, never()).evict(anyString());
        }
    }

    // ── SendInviteMessage ─────────────────────────────────────────

    @Nested
    class SendInviteMessage {

        @SuppressWarnings("unchecked")
        @Test
        void publishes_SYSTEM_payload_with_inviter_and_target_in_content() {
            roomMembershipService.sendInviteMessage(ROOM_ID, "Alice", "Bob");

            ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
            verify(messagingTemplate).convertAndSend(
                    eq("/topic/chat/" + ROOM_ID), payloadCaptor.capture());

            Map<String, Object> payload = payloadCaptor.getValue();
            assertEquals("SYSTEM", payload.get("type"));
            assertEquals("SYSTEM", payload.get("username"));
            String content = payload.get("content").toString();
            assertTrue(content.contains("Alice"), "content should contain inviter name");
            assertTrue(content.contains("Bob"), "content should contain target name");
        }
    }
}
