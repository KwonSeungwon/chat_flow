package com.chatflow.chat.service.read;

import com.chatflow.chat.repository.ChatMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for UnreadCountService — durable cursor rewrite.
 *
 * <p>After U2, UnreadCountService uses a single {@code countUnreadByCursor}
 * repository call (theta join against room_members.last_read_at). Redis
 * dependencies are fully removed.
 */
@ExtendWith(MockitoExtension.class)
class UnreadCountServiceTest {

    @Mock private ChatMessageRepository chatMessageRepository;

    private UnreadCountService unreadCountService;

    private static final String USER_ID = "user-1";
    private static final String ROOM_A = "room-a";
    private static final String ROOM_B = "room-b";
    private static final String ROOM_C = "room-c";

    @BeforeEach
    void setUp() {
        // Structural: constructor takes ONLY ChatMessageRepository
        unreadCountService = new UnreadCountService(chatMessageRepository);
    }

    // ── GetUnreadCounts ─────────────────────────────────────────────

    @Nested
    class GetUnreadCounts {

        @Test
        void single_repo_call_returns_counts() {
            // countUnreadByCursor returns rows for rooms with unread messages
            List<Object[]> rows = List.of(
                    new Object[]{ROOM_A, 5L},
                    new Object[]{ROOM_B, 3L});
            when(chatMessageRepository.countUnreadByCursor(eq(USER_ID), anyList()))
                    .thenReturn(rows);

            Map<String, Long> result = unreadCountService.getUnreadCounts(
                    USER_ID, List.of(ROOM_A, ROOM_B, ROOM_C));

            // Exactly ONE repository call
            verify(chatMessageRepository, times(1))
                    .countUnreadByCursor(eq(USER_ID), eq(List.of(ROOM_A, ROOM_B, ROOM_C)));

            // All requested roomIds present with correct counts
            assertEquals(3, result.size());
            assertEquals(5L, result.get(ROOM_A));
            assertEquals(3L, result.get(ROOM_B));
            // ROOM_C absent from repo result → initialized to 0L
            assertEquals(0L, result.get(ROOM_C));
        }

        @Test
        void preserves_roomIds_order() {
            // Request order: C, A, B — result map must iterate in that order
            when(chatMessageRepository.countUnreadByCursor(eq(USER_ID), anyList()))
                    .thenReturn(Collections.singletonList(new Object[]{ROOM_A, 1L}));

            Map<String, Long> result = unreadCountService.getUnreadCounts(
                    USER_ID, List.of(ROOM_C, ROOM_A, ROOM_B));

            List<String> keys = new ArrayList<>(result.keySet());
            assertEquals(List.of(ROOM_C, ROOM_A, ROOM_B), keys);
        }

        @Test
        void all_zeros_when_no_unread() {
            // Repo returns empty list (all rooms all-read)
            when(chatMessageRepository.countUnreadByCursor(eq(USER_ID), anyList()))
                    .thenReturn(Collections.emptyList());

            Map<String, Long> result = unreadCountService.getUnreadCounts(
                    USER_ID, List.of(ROOM_A, ROOM_B));

            assertEquals(2, result.size());
            assertEquals(0L, result.get(ROOM_A));
            assertEquals(0L, result.get(ROOM_B));
        }

        @Test
        void empty_roomIds_returns_empty_map_no_query() {
            Map<String, Long> result = unreadCountService.getUnreadCounts(
                    USER_ID, List.of());

            assertTrue(result.isEmpty());
            // No repository interaction at all
            verifyNoInteractions(chatMessageRepository);
        }

        @Test
        void query_throws_returns_all_zeros_no_exception() {
            when(chatMessageRepository.countUnreadByCursor(eq(USER_ID), anyList()))
                    .thenThrow(new RuntimeException("DB connection lost"));

            Map<String, Long> result = assertDoesNotThrow(() ->
                    unreadCountService.getUnreadCounts(USER_ID, List.of(ROOM_A, ROOM_B)));

            // Fail-soft: all zeros, endpoint must not 500
            assertEquals(2, result.size());
            assertEquals(0L, result.get(ROOM_A));
            assertEquals(0L, result.get(ROOM_B));
        }
    }
}
