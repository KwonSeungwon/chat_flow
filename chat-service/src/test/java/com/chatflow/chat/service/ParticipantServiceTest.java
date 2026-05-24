package com.chatflow.chat.service;

import com.chatflow.chat.config.RedisHealthTracker;
import com.chatflow.chat.entity.ChatRoom;
import com.chatflow.chat.repository.ChatRoomRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ParticipantServiceTest {

    @Mock private ChatRoomRepository chatRoomRepository;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private RedisHealthTracker redisHealth;
    @Mock private RoomCacheEvictor roomCacheEvictor;
    @Mock private SetOperations<String, String> setOperations;

    private ParticipantService participantService;

    private static final String ROOM_ID = "room-1";
    private static final String PARTICIPANT_KEY = "chatflow:room:participants:" + ROOM_ID;

    @BeforeEach
    void setUp() {
        participantService = new ParticipantService(
                chatRoomRepository, redisTemplate, redisHealth, roomCacheEvictor);
    }

    // ── isRoomFull ──────────────────────────────────────────────

    @Nested
    class IsRoomFullTests {

        @Test
        void isRoomFull_returns_true_when_participant_count_equals_max() {
            when(chatRoomRepository.isRoomFull(ROOM_ID)).thenReturn(true);

            assertTrue(participantService.isRoomFull(ROOM_ID));

            verify(chatRoomRepository).isRoomFull(ROOM_ID);
        }

        @Test
        void isRoomFull_returns_false_when_below_max() {
            when(chatRoomRepository.isRoomFull(ROOM_ID)).thenReturn(false);

            assertFalse(participantService.isRoomFull(ROOM_ID));
        }

        @Test
        void isRoomFull_returns_true_on_repository_exception() {
            when(chatRoomRepository.isRoomFull(ROOM_ID))
                    .thenThrow(new RuntimeException("DB unavailable"));

            assertTrue(participantService.isRoomFull(ROOM_ID));
        }
    }

    // ── syncParticipantCountFromRedis ───────────────────────────

    @Nested
    class SyncParticipantCountFromRedisTests {

        @Test
        void syncParticipantCountFromRedis_writes_distinct_userId_count_to_chat_rooms_table() {
            // 10 entries, all distinct userIds → uniqueUserCount = 10
            Set<String> members = IntStream.range(0, 10)
                    .mapToObj(i -> "user" + i + ":session" + i + ":username" + i)
                    .collect(Collectors.toSet());

            when(redisHealth.isCircuitOpen()).thenReturn(false);
            when(redisTemplate.opsForSet()).thenReturn(setOperations);
            when(setOperations.members(PARTICIPANT_KEY)).thenReturn(members);

            ChatRoom room = ChatRoom.builder().id(ROOM_ID).name("test").participantCount(0).build();
            when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));

            participantService.syncParticipantCountFromRedis(ROOM_ID);

            verify(redisHealth).recordSuccess();
            assertEquals(10, room.getParticipantCount());
        }

        @Test
        void syncParticipantCountFromRedis_dedupes_userIds_across_sessions() {
            // 11 entries but only 9 unique userIds (user0 and user1 have extra sessions)
            Set<String> members = IntStream.range(0, 9)
                    .mapToObj(i -> "user" + i + ":sessionA:username" + i)
                    .collect(Collectors.toSet());
            members.add("user0:sessionB:username0");   // duplicate userId
            members.add("user1:sessionC:username1");   // duplicate userId

            assertEquals(11, members.size());  // precondition: 11 total entries

            when(redisHealth.isCircuitOpen()).thenReturn(false);
            when(redisTemplate.opsForSet()).thenReturn(setOperations);
            when(setOperations.members(PARTICIPANT_KEY)).thenReturn(members);

            ChatRoom room = ChatRoom.builder().id(ROOM_ID).name("test").participantCount(5).build();
            when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));

            participantService.syncParticipantCountFromRedis(ROOM_ID);

            // 9 unique userIds, not 11
            assertEquals(9, room.getParticipantCount());
            verify(redisHealth).recordSuccess();
        }

        @Test
        void syncParticipantCountFromRedis_falls_back_to_decrement_on_redis_failure() {
            when(redisHealth.isCircuitOpen()).thenReturn(false);
            when(redisTemplate.opsForSet()).thenReturn(setOperations);
            when(setOperations.members(PARTICIPANT_KEY)).thenThrow(new RuntimeException("Redis down"));

            participantService.syncParticipantCountFromRedis(ROOM_ID);

            verify(redisHealth).recordFailure(any(RuntimeException.class));
            verify(chatRoomRepository).decrementParticipantCount(ROOM_ID);
            verify(chatRoomRepository, never()).findById(anyString());
        }

        @Test
        void syncParticipantCountFromRedis_falls_back_to_decrement_when_circuit_open() {
            when(redisHealth.isCircuitOpen()).thenReturn(true);

            participantService.syncParticipantCountFromRedis(ROOM_ID);

            verify(chatRoomRepository).decrementParticipantCount(ROOM_ID);
            verify(redisTemplate, never()).opsForSet();
        }
    }
}
