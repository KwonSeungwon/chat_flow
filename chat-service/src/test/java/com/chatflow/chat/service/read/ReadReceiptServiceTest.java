package com.chatflow.chat.service.read;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ReadReceiptService covering Redis key writes
 * (chatflow:read: and chatflow:readat: prefixes) and STOMP broadcast.
 */
@ExtendWith(MockitoExtension.class)
class ReadReceiptServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    private ReadReceiptService readReceiptService;

    private static final String USER_ID = "user-1";
    private static final String USERNAME = "alice";
    private static final String ROOM_ID = "room-42";
    private static final String MSG_ID = "msg-100";

    @BeforeEach
    void setUp() {
        readReceiptService = new ReadReceiptService(redisTemplate, messagingTemplate);
    }

    // ── helpers ─────────────────────────────────────────────────────

    /**
     * Prepares a mock Cursor that yields the given keys, then stubs
     * redisTemplate.scan() to return it.  Built outside the when() chain
     * to avoid Mockito's nested-stubbing detection.
     */
    @SuppressWarnings("unchecked")
    private void stubScanKeys(List<String> keys) {
        Cursor<String> cursor = mock(Cursor.class);
        doAnswer(inv -> {
            java.util.function.Consumer<String> action = inv.getArgument(0);
            keys.forEach(action);
            return null;
        }).when(cursor).forEachRemaining(any());

        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);
    }

    // ── MarkRead ───────────────────────────────────────────────────

    @Nested
    class MarkRead {

        @Test
        void markRead_writes_lastReadMessageId_and_readAt_keys() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            stubScanKeys(Collections.emptyList());

            readReceiptService.markRead(ROOM_ID, USER_ID, USERNAME, MSG_ID);

            // chatflow:read:{roomId}:{userId} — lastReadMessageId
            verify(valueOperations).set(
                    eq("chatflow:read:" + ROOM_ID + ":" + USER_ID),
                    eq(MSG_ID),
                    eq(24L),
                    eq(TimeUnit.HOURS));

            // chatflow:readat:{roomId}:{userId} — readAt timestamp
            verify(valueOperations).set(
                    eq("chatflow:readat:" + ROOM_ID + ":" + USER_ID),
                    argThat(ts -> ts != null && !ts.isEmpty()),
                    eq(24L),
                    eq(TimeUnit.HOURS));
        }

        @Test
        @SuppressWarnings("unchecked")
        void markRead_broadcasts_read_receipt_on_topic() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            // Scan returns current user's key so positions map is non-empty
            stubScanKeys(List.of("chatflow:read:" + ROOM_ID + ":" + USER_ID));
            when(valueOperations.get("chatflow:read:" + ROOM_ID + ":" + USER_ID))
                    .thenReturn(MSG_ID);

            readReceiptService.markRead(ROOM_ID, USER_ID, USERNAME, MSG_ID);

            ArgumentCaptor<String> destCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
            verify(messagingTemplate).convertAndSend(destCaptor.capture(), payloadCaptor.capture());

            // Destination: /topic/chat/{roomId}/read-receipts
            assertEquals("/topic/chat/" + ROOM_ID + "/read-receipts", destCaptor.getValue());

            // Payload is a Map with expected keys — production sends a raw Map,
            // not a typed DTO, and has no explicit "type" field like READ_RECEIPT.
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
    }

    // ── UpdateReadAt ───────────────────────────────────────────────

    @Nested
    class UpdateReadAt {

        @Test
        void updateReadAt_writes_only_readAt_key() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);

            readReceiptService.updateReadAt(ROOM_ID, USER_ID);

            // chatflow:readat: key IS written
            verify(valueOperations).set(
                    eq("chatflow:readat:" + ROOM_ID + ":" + USER_ID),
                    argThat(ts -> ts != null && !ts.isEmpty()),
                    eq(24L),
                    eq(TimeUnit.HOURS));

            // chatflow:read: key is NOT written — only 1 call total
            verify(valueOperations, times(1)).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        }
    }

    // ── GetRoomReadPositions ───────────────────────────────────────

    @Nested
    class GetRoomReadPositions {

        @Test
        void getRoomReadPositions_returns_userId_to_messageId_map() {
            // Production signature: getRoomReadPositions(roomId) — no caller
            // exclusion parameter. Returns full map of all users.
            String keyA = "chatflow:read:" + ROOM_ID + ":user-a";
            String keyB = "chatflow:read:" + ROOM_ID + ":user-b";

            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            stubScanKeys(List.of(keyA, keyB));
            when(valueOperations.get(keyA)).thenReturn("msg-10");
            when(valueOperations.get(keyB)).thenReturn("msg-20");

            Map<String, String> positions = readReceiptService.getRoomReadPositions(ROOM_ID);

            assertEquals(2, positions.size());
            assertEquals("msg-10", positions.get("user-a"));
            assertEquals("msg-20", positions.get("user-b"));
        }
    }
}
