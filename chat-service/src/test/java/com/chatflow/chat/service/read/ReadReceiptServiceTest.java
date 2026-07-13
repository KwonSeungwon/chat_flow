package com.chatflow.chat.service.read;

import com.chatflow.chat.config.RedisHealthTracker;
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
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ReadReceiptService covering Redis hash writes
 * (chatflow:read:{roomId} hash + chatflow:readat: string keys) and STOMP broadcast.
 *
 * <p>Read positions are stored as a per-room Redis HASH (field=userId, value=lastReadMessageId).
 * readAt timestamps remain per-user string keys (consumed by UnreadCountService).
 */
@ExtendWith(MockitoExtension.class)
class ReadReceiptServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private RedisHealthTracker redisHealth;
    @Mock private ValueOperations<String, String> valueOperations;
    @SuppressWarnings("rawtypes")
    @Mock private HashOperations hashOperations;

    private ReadReceiptService readReceiptService;

    private static final String USER_ID = "user-1";
    private static final String USERNAME = "alice";
    private static final String ROOM_ID = "room-42";
    private static final String MSG_ID = "msg-100";

    @BeforeEach
    void setUp() {
        readReceiptService = new ReadReceiptService(redisTemplate, messagingTemplate, redisHealth);
    }

    // ── MarkRead ───────────────────────────────────────────────────

    @Nested
    class MarkRead {

        @SuppressWarnings("unchecked")
        @Test
        void markRead_writes_hash_field_and_readAt_key() {
            when(redisHealth.isCircuitOpen()).thenReturn(false);
            when(redisTemplate.opsForHash()).thenReturn(hashOperations);
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            // HGETALL for positions broadcast (returns the just-written entry)
            Map<Object, Object> entries = new LinkedHashMap<>();
            entries.put(USER_ID, MSG_ID);
            when(hashOperations.entries("chatflow:read:" + ROOM_ID)).thenReturn(entries);

            readReceiptService.markRead(ROOM_ID, USER_ID, USERNAME, MSG_ID);

            // chatflow:read:{roomId} hash — HSET userId -> lastReadMessageId
            verify(hashOperations).put("chatflow:read:" + ROOM_ID, USER_ID, MSG_ID);

            // Hash TTL refreshed on each write
            verify(redisTemplate).expire(eq("chatflow:read:" + ROOM_ID), eq(24L), eq(TimeUnit.HOURS));

            // chatflow:readat:{roomId}:{userId} — readAt timestamp (unchanged)
            verify(valueOperations).set(
                    eq("chatflow:readat:" + ROOM_ID + ":" + USER_ID),
                    argThat(ts -> ts != null && !ts.isEmpty()),
                    eq(24L),
                    eq(TimeUnit.HOURS));

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
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
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

            // Failure recorded on the circuit breaker
            verify(redisHealth).recordFailure(any(Exception.class));
            // No broadcast after a failed write
            verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        }

        @SuppressWarnings("unchecked")
        @Test
        void markRead_writesSucceed_readBackEmpty_noBroadcast() {
            // Writes succeed, but HGETALL returns empty (Redis died between write and read-back)
            when(redisHealth.isCircuitOpen()).thenReturn(false);
            when(redisTemplate.opsForHash()).thenReturn(hashOperations);
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
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
        void updateReadAt_writes_only_readAt_key() {
            when(redisHealth.isCircuitOpen()).thenReturn(false);
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);

            readReceiptService.updateReadAt(ROOM_ID, USER_ID);

            // chatflow:readat: key IS written
            verify(valueOperations).set(
                    eq("chatflow:readat:" + ROOM_ID + ":" + USER_ID),
                    argThat(ts -> ts != null && !ts.isEmpty()),
                    eq(24L),
                    eq(TimeUnit.HOURS));

            // chatflow:read: hash is NOT touched
            verify(redisTemplate, never()).opsForHash();
            // only 1 opsForValue().set() call total
            verify(valueOperations, times(1)).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
            // Circuit breaker recorded success
            verify(redisHealth).recordSuccess();
        }

        @Test
        void updateReadAt_circuitOpen_skipsRedis() {
            when(redisHealth.isCircuitOpen()).thenReturn(true);

            assertDoesNotThrow(() -> readReceiptService.updateReadAt(ROOM_ID, USER_ID));

            verify(redisTemplate, never()).opsForValue();
        }

        @Test
        void updateReadAt_redisThrows_swallowsException() {
            when(redisHealth.isCircuitOpen()).thenReturn(false);
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            doThrow(new RedisConnectionFailureException("connection refused"))
                    .when(valueOperations).set(anyString(), anyString(), anyLong(), any());

            assertDoesNotThrow(() -> readReceiptService.updateReadAt(ROOM_ID, USER_ID));

            verify(redisHealth).recordFailure(any(Exception.class));
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
