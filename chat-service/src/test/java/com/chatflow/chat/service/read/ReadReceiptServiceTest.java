package com.chatflow.chat.service.read;

import com.chatflow.chat.config.RedisHealthTracker;
import com.chatflow.chat.repository.RoomMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ReadReceiptService covering:
 * <ul>
 *   <li>DB cursor touch via {@code roomMemberRepository.touchLastReadAt}</li>
 *   <li>Redis positions hash ({@code chatflow:read:{roomId}}) writes + STOMP broadcast</li>
 *   <li>#18 circuit-breaker / fail-soft behaviors preserved</li>
 * </ul>
 *
 * <p>After U2, {@code chatflow:readat:*} string keys are no longer written.
 * The durable cursor lives in {@code room_members.last_read_at} (DB).
 */
@ExtendWith(MockitoExtension.class)
class ReadReceiptServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private RedisHealthTracker redisHealth;
    @Mock private RoomMemberRepository roomMemberRepository;
    @SuppressWarnings("rawtypes")
    @Mock private HashOperations hashOperations;

    private ReadReceiptService readReceiptService;

    private static final String USER_ID = "user-1";
    private static final String USERNAME = "alice";
    private static final String ROOM_ID = "room-42";
    private static final String MSG_ID = "msg-100";

    @BeforeEach
    void setUp() {
        readReceiptService = new ReadReceiptService(
                redisTemplate, messagingTemplate, redisHealth, roomMemberRepository);
    }

    // ── MarkRead ───────────────────────────────────────────────────

    @Nested
    class MarkRead {

        @SuppressWarnings("unchecked")
        @Test
        void markRead_writes_hash_field_and_touches_cursor() {
            when(redisHealth.isCircuitOpen()).thenReturn(false);
            when(redisTemplate.opsForHash()).thenReturn(hashOperations);
            // HGETALL for positions broadcast (returns the just-written entry)
            Map<Object, Object> entries = new LinkedHashMap<>();
            entries.put(USER_ID, MSG_ID);
            when(hashOperations.entries("chatflow:read:" + ROOM_ID)).thenReturn(entries);

            readReceiptService.markRead(ROOM_ID, USER_ID, USERNAME, MSG_ID);

            // DB cursor touch (roomMemberRepository.touchLastReadAt)
            verify(roomMemberRepository).touchLastReadAt(
                    eq(ROOM_ID), eq(USER_ID), any(LocalDateTime.class));

            // chatflow:read:{roomId} hash — HSET userId -> lastReadMessageId
            verify(hashOperations).put("chatflow:read:" + ROOM_ID, USER_ID, MSG_ID);

            // Hash TTL refreshed on each write
            verify(redisTemplate).expire(eq("chatflow:read:" + ROOM_ID), eq(24L), eq(TimeUnit.HOURS));

            // NO chatflow:readat: key written (readat keys retired)
            verify(redisTemplate, never()).opsForValue();

            // Circuit breaker recorded success
            verify(redisHealth).recordSuccess();

            // No SCAN should be invoked
            verify(redisTemplate, never()).scan(any());
        }

        @Test
        @SuppressWarnings("unchecked")
        void markRead_broadcasts_read_receipt_on_topic() {
            when(redisHealth.isCircuitOpen()).thenReturn(false);
            when(redisTemplate.opsForHash()).thenReturn(hashOperations);
            // HGETALL returns current user's position
            Map<Object, Object> entries = new LinkedHashMap<>();
            entries.put(USER_ID, MSG_ID);
            when(hashOperations.entries("chatflow:read:" + ROOM_ID)).thenReturn(entries);

            readReceiptService.markRead(ROOM_ID, USER_ID, USERNAME, MSG_ID);

            ArgumentCaptor<String> destCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
            verify(messagingTemplate).convertAndSend(destCaptor.capture(), payloadCaptor.capture());

            // Destination: /topic/chat/{roomId}/read-receipts
            assertEquals("/topic/chat/" + ROOM_ID + "/read-receipts", destCaptor.getValue());

            // Payload is a Map with expected keys
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) payloadCaptor.getValue();
            assertEquals(USER_ID, payload.get("userId"));
            assertEquals(USERNAME, payload.get("username"));
            assertEquals(ROOM_ID, payload.get("roomId"));
            assertEquals(MSG_ID, payload.get("messageId"));
            assertEquals(MSG_ID, payload.get("lastReadMessageId"));
            assertNotNull(payload.get("timestamp"));
            assertNotNull(payload.get("positions"));
            assertNotNull(payload.get("readCount"));
        }

        @Test
        void markRead_circuitOpen_skips_redis_and_broadcast() {
            when(redisHealth.isCircuitOpen()).thenReturn(true);

            assertDoesNotThrow(() ->
                    readReceiptService.markRead(ROOM_ID, USER_ID, USERNAME, MSG_ID));

            // DB cursor touch still happens (circuit is for Redis, not DB)
            verify(roomMemberRepository).touchLastReadAt(
                    eq(ROOM_ID), eq(USER_ID), any(LocalDateTime.class));

            // No Redis ops attempted
            verify(redisTemplate, never()).opsForHash();
            verify(redisTemplate, never()).opsForValue();
            verify(redisTemplate, never()).expire(anyString(), anyLong(), any());
            // No broadcast — stale/empty positions would mislead clients
            verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        }

        @SuppressWarnings("unchecked")
        @Test
        void markRead_redisThrows_swallowsException_noBroadcast() {
            when(redisHealth.isCircuitOpen()).thenReturn(false);
            when(redisTemplate.opsForHash()).thenReturn(hashOperations);
            doThrow(new RedisConnectionFailureException("connection refused"))
                    .when(hashOperations).put(anyString(), any(), any());

            assertDoesNotThrow(() ->
                    readReceiptService.markRead(ROOM_ID, USER_ID, USERNAME, MSG_ID));

            // DB cursor touch still happened (before the Redis block)
            verify(roomMemberRepository).touchLastReadAt(
                    eq(ROOM_ID), eq(USER_ID), any(LocalDateTime.class));

            // Failure recorded on the circuit breaker
            verify(redisHealth).recordFailure(any(Exception.class));
            // No broadcast after a failed write
            verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        }

        @SuppressWarnings("unchecked")
        @Test
        void markRead_dbCursorThrows_swallowed_redisStillAttempted() {
            // DB cursor touch fails → swallowed (warn log), Redis block still runs
            doThrow(new RuntimeException("DB connection lost"))
                    .when(roomMemberRepository).touchLastReadAt(anyString(), anyString(), any());

            when(redisHealth.isCircuitOpen()).thenReturn(false);
            when(redisTemplate.opsForHash()).thenReturn(hashOperations);
            Map<Object, Object> entries = new LinkedHashMap<>();
            entries.put(USER_ID, MSG_ID);
            when(hashOperations.entries("chatflow:read:" + ROOM_ID)).thenReturn(entries);

            assertDoesNotThrow(() ->
                    readReceiptService.markRead(ROOM_ID, USER_ID, USERNAME, MSG_ID));

            // Redis positions hash still written
            verify(hashOperations).put("chatflow:read:" + ROOM_ID, USER_ID, MSG_ID);
            // Broadcast still happens
            verify(messagingTemplate).convertAndSend(anyString(), any(Object.class));
        }

        @SuppressWarnings("unchecked")
        @Test
        void markRead_writesSucceed_readBackEmpty_noBroadcast() {
            // Writes succeed, but HGETALL returns empty (Redis died between write and read-back)
            when(redisHealth.isCircuitOpen()).thenReturn(false);
            when(redisTemplate.opsForHash()).thenReturn(hashOperations);
            // Read-back returns empty — simulates Redis failure in getRoomReadPositions (fail-soft → empty map)
            when(hashOperations.entries("chatflow:read:" + ROOM_ID)).thenReturn(new LinkedHashMap<>());

            readReceiptService.markRead(ROOM_ID, USER_ID, USERNAME, MSG_ID);

            // Writes did happen
            verify(hashOperations).put("chatflow:read:" + ROOM_ID, USER_ID, MSG_ID);
            verify(redisHealth).recordSuccess();
            // But no broadcast — empty positions would wipe read markers on all clients
            verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        }
    }

    // ── UpdateReadAt ───────────────────────────────────────────────

    @Nested
    class UpdateReadAt {

        @Test
        void updateReadAt_touches_db_cursor() {
            readReceiptService.updateReadAt(ROOM_ID, USER_ID);

            // DB cursor touch
            verify(roomMemberRepository).touchLastReadAt(
                    eq(ROOM_ID), eq(USER_ID), any(LocalDateTime.class));

            // NO chatflow:readat: key written (readat keys retired)
            verify(redisTemplate, never()).opsForValue();
            // NO Redis hash touched
            verify(redisTemplate, never()).opsForHash();
        }

        @Test
        void updateReadAt_dbThrows_swallowsException() {
            doThrow(new RuntimeException("DB connection lost"))
                    .when(roomMemberRepository).touchLastReadAt(anyString(), anyString(), any());

            assertDoesNotThrow(() -> readReceiptService.updateReadAt(ROOM_ID, USER_ID));
        }
    }

    // ── GetRoomReadPositions ───────────────────────────────────────

    @Nested
    class GetRoomReadPositions {

        @SuppressWarnings("unchecked")
        @Test
        void getRoomReadPositions_returns_userId_to_messageId_map_via_HGETALL() {
            when(redisTemplate.opsForHash()).thenReturn(hashOperations);
            Map<Object, Object> entries = new LinkedHashMap<>();
            entries.put("user-a", "msg-10");
            entries.put("user-b", "msg-20");
            when(hashOperations.entries("chatflow:read:" + ROOM_ID)).thenReturn(entries);

            Map<String, String> positions = readReceiptService.getRoomReadPositions(ROOM_ID);

            assertEquals(2, positions.size());
            assertEquals("msg-10", positions.get("user-a"));
            assertEquals("msg-20", positions.get("user-b"));

            // No SCAN should be used
            verify(redisTemplate, never()).scan(any());
        }
    }

    // ── GetLastReadMessageId ──────────────────────────────────────

    @Nested
    class GetLastReadMessageId {

        @SuppressWarnings("unchecked")
        @Test
        void returns_value_from_hash_field() {
            when(redisHealth.isCircuitOpen()).thenReturn(false);
            when(redisTemplate.opsForHash()).thenReturn(hashOperations);
            when(hashOperations.get("chatflow:read:" + ROOM_ID, USER_ID)).thenReturn(MSG_ID);

            String result = readReceiptService.getLastReadMessageId(ROOM_ID, USER_ID);

            assertEquals(MSG_ID, result);
            verify(redisHealth).recordSuccess();
        }

        @SuppressWarnings("unchecked")
        @Test
        void returns_null_when_hash_field_absent() {
            when(redisHealth.isCircuitOpen()).thenReturn(false);
            when(redisTemplate.opsForHash()).thenReturn(hashOperations);
            when(hashOperations.get("chatflow:read:" + ROOM_ID, USER_ID)).thenReturn(null);

            String result = readReceiptService.getLastReadMessageId(ROOM_ID, USER_ID);

            assertNull(result);
        }

        @Test
        void returns_null_when_circuit_open() {
            when(redisHealth.isCircuitOpen()).thenReturn(true);

            String result = readReceiptService.getLastReadMessageId(ROOM_ID, USER_ID);

            assertNull(result);
            verify(redisTemplate, never()).opsForHash();
        }

        @SuppressWarnings("unchecked")
        @Test
        void returns_null_when_redis_throws() {
            when(redisHealth.isCircuitOpen()).thenReturn(false);
            when(redisTemplate.opsForHash()).thenReturn(hashOperations);
            when(hashOperations.get(anyString(), any()))
                    .thenThrow(new RedisConnectionFailureException("connection refused"));

            String result = assertDoesNotThrow(() ->
                    readReceiptService.getLastReadMessageId(ROOM_ID, USER_ID));

            assertNull(result);
            verify(redisHealth).recordFailure(any(Exception.class));
        }
    }
}
