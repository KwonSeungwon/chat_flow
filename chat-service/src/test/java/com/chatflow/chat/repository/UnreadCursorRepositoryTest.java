package com.chatflow.chat.repository;

import com.chatflow.chat.entity.ChatMessageEntity;
import com.chatflow.chat.entity.RoomMemberEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the durable read cursor (V12) — verifies:
 * <ul>
 *   <li>{@code countUnreadByCursor}: theta-join grouped count with
 *       COALESCE(lastReadAt, joinedAt) cutoff</li>
 *   <li>{@code touchLastReadAt}: bulk cursor update + re-query reflects
 *       moved cursor</li>
 * </ul>
 */
@DataJpaTest
@ContextConfiguration(classes = RepositoryTestConfig.class)
@ActiveProfiles("test")
class UnreadCursorRepositoryTest {

    @Autowired
    private ChatMessageRepository messageRepository;

    @Autowired
    private RoomMemberRepository memberRepository;

    private static final String USER_ID = "user-1";
    private static final String ROOM_A = "room-a";
    private static final String ROOM_B = "room-b";
    private static final String ROOM_C = "room-c"; // user is NOT a member

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 7, 1, 12, 0, 0);

    @BeforeEach
    void setUp() {
        messageRepository.deleteAll();
        memberRepository.deleteAll();
    }

    // ---- countUnreadByCursor ----

    @Test
    void countUnreadByCursor_differentCursors_returnsPerRoomCounts() {
        // Room A: joined 10h ago, last read 2h ago → 2 messages after cursor
        memberRepository.save(member(ROOM_A, USER_ID, BASE.minusHours(10), BASE.minusHours(2)));
        // messages: 3h ago (before cursor), 1h ago (after), 30min ago (after)
        saveChat(ROOM_A, BASE.minusHours(3));
        saveChat(ROOM_A, BASE.minusHours(1));
        saveChat(ROOM_A, BASE.minusMinutes(30));

        // Room B: joined 5h ago, last read 4h ago → 1 message after cursor
        memberRepository.save(member(ROOM_B, USER_ID, BASE.minusHours(5), BASE.minusHours(4)));
        // messages: 6h ago (before join), 3h ago (after cursor), system msg at 2h ago
        saveChat(ROOM_B, BASE.minusHours(6));
        saveChat(ROOM_B, BASE.minusHours(3));
        saveMessage(ROOM_B, BASE.minusHours(2), "SYSTEM", false); // type != CHAT

        List<Object[]> results = messageRepository.countUnreadByCursor(
                USER_ID, List.of(ROOM_A, ROOM_B));

        Map<String, Long> counts = toCountMap(results);
        assertThat(counts.get(ROOM_A)).isEqualTo(2L);
        assertThat(counts.get(ROOM_B)).isEqualTo(1L);
    }

    @Test
    void countUnreadByCursor_nonMemberRoom_absentFromResults() {
        // User is member of A but not C
        memberRepository.save(member(ROOM_A, USER_ID, BASE.minusHours(10), BASE.minusHours(1)));
        saveChat(ROOM_A, BASE.minusMinutes(30));

        // Room C has messages but user is NOT a member
        saveChat(ROOM_C, BASE.minusMinutes(30));

        List<Object[]> results = messageRepository.countUnreadByCursor(
                USER_ID, List.of(ROOM_A, ROOM_C));

        Map<String, Long> counts = toCountMap(results);
        assertThat(counts).containsKey(ROOM_A);
        assertThat(counts).doesNotContainKey(ROOM_C);
    }

    @Test
    void countUnreadByCursor_nullLastReadAt_countsFromJoinedAt() {
        // lastReadAt = NULL → COALESCE falls back to joinedAt (5h ago)
        memberRepository.save(member(ROOM_A, USER_ID, BASE.minusHours(5), null));

        // messages: 6h ago (before join), 4h ago (after join), 1h ago (after join)
        saveChat(ROOM_A, BASE.minusHours(6));
        saveChat(ROOM_A, BASE.minusHours(4));
        saveChat(ROOM_A, BASE.minusHours(1));

        List<Object[]> results = messageRepository.countUnreadByCursor(
                USER_ID, List.of(ROOM_A));

        Map<String, Long> counts = toCountMap(results);
        assertThat(counts.get(ROOM_A)).isEqualTo(2L);
    }

    @Test
    void countUnreadByCursor_excludesDeletedMessages() {
        memberRepository.save(member(ROOM_A, USER_ID, BASE.minusHours(10), BASE.minusHours(2)));

        saveChat(ROOM_A, BASE.minusHours(1));                      // unread
        saveMessage(ROOM_A, BASE.minusMinutes(30), "CHAT", true);  // deleted

        List<Object[]> results = messageRepository.countUnreadByCursor(
                USER_ID, List.of(ROOM_A));

        Map<String, Long> counts = toCountMap(results);
        assertThat(counts.get(ROOM_A)).isEqualTo(1L);
    }

    @Test
    void countUnreadByCursor_allRead_roomAbsentFromResults() {
        // cursor is after all messages → 0 unread → room absent (no GROUP BY row)
        memberRepository.save(member(ROOM_A, USER_ID, BASE.minusHours(10), BASE));
        saveChat(ROOM_A, BASE.minusHours(1));

        List<Object[]> results = messageRepository.countUnreadByCursor(
                USER_ID, List.of(ROOM_A));

        assertThat(results).isEmpty();
    }

    @Test
    void countUnreadByCursor_emptyRoomIds_returnsEmptyList() {
        List<Object[]> results = messageRepository.countUnreadByCursor(
                USER_ID, List.of());

        assertThat(results).isEmpty();
    }

    // ---- touchLastReadAt ----

    @Test
    @Transactional
    void touchLastReadAt_updatesCursorAndAffectsUnreadCount() {
        // initial: lastReadAt 2h ago, 2 unread messages
        memberRepository.save(member(ROOM_A, USER_ID, BASE.minusHours(10), BASE.minusHours(2)));
        saveChat(ROOM_A, BASE.minusHours(1));
        saveChat(ROOM_A, BASE.minusMinutes(30));

        // verify 2 unread
        assertThat(toCountMap(messageRepository.countUnreadByCursor(
                USER_ID, List.of(ROOM_A))).get(ROOM_A)).isEqualTo(2L);

        // touch cursor to now → all read
        int updated = memberRepository.touchLastReadAt(ROOM_A, USER_ID, BASE);
        assertThat(updated).isEqualTo(1);

        // re-query: 0 unread → absent from results
        List<Object[]> results = messageRepository.countUnreadByCursor(
                USER_ID, List.of(ROOM_A));
        assertThat(results).isEmpty();
    }

    @Test
    @Transactional
    void touchLastReadAt_noMatchingRow_returnsZero() {
        int updated = memberRepository.touchLastReadAt("no-room", "no-user", BASE);
        assertThat(updated).isZero();
    }

    // ---- helpers ----

    private RoomMemberEntity member(String roomId, String userId,
                                     LocalDateTime joinedAt, LocalDateTime lastReadAt) {
        return RoomMemberEntity.builder()
                .roomId(roomId)
                .userId(userId)
                .username("user-" + userId)
                .joinedAt(joinedAt)
                .lastReadAt(lastReadAt)
                .build();
    }

    private void saveChat(String roomId, LocalDateTime timestamp) {
        saveMessage(roomId, timestamp, "CHAT", false);
    }

    private void saveMessage(String roomId, LocalDateTime timestamp,
                              String type, boolean deleted) {
        messageRepository.save(ChatMessageEntity.builder()
                .messageId(UUID.randomUUID().toString())
                .chatRoomId(roomId)
                .userId("sender-1")
                .username("sender")
                .content("test message")
                .timestamp(timestamp)
                .type(type)
                .deleted(deleted)
                .build());
    }

    private Map<String, Long> toCountMap(List<Object[]> rows) {
        return rows.stream().collect(Collectors.toMap(
                r -> (String) r[0],
                r -> (Long) r[1]));
    }
}
