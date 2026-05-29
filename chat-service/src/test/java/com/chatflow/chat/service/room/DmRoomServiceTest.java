package com.chatflow.chat.service.room;

import com.chatflow.chat.entity.ChatRoom;
import com.chatflow.chat.entity.RoomType;
import com.chatflow.chat.repository.ChatRoomRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DmRoomService covering idempotent create-or-find,
 * canonical pair ordering, room type assignment, and cache eviction.
 */
@ExtendWith(MockitoExtension.class)
class DmRoomServiceTest {

    @Mock private ChatRoomRepository chatRoomRepository;
    @Mock private RoomCacheEvictor roomCacheEvictor;

    private DmRoomService dmRoomService;

    private static final String USER_ID_1 = "uid-alice";
    private static final String USERNAME_1 = "alice";
    private static final String USER_ID_2 = "uid-bob";
    private static final String USERNAME_2 = "bob";

    @BeforeEach
    void setUp() {
        dmRoomService = new DmRoomService(chatRoomRepository, roomCacheEvictor);
    }

    private ChatRoom sampleDmRoom(String name) {
        return ChatRoom.builder()
                .id("dm-room-1")
                .name(name)
                .description(USERNAME_1 + "님과 " + USERNAME_2 + "님의 대화")
                .roomType(RoomType.DIRECT)
                .participantCount(0)
                .maxParticipants(2)
                .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                .build();
    }

    // -- CreateOrFindDmRoom -----------------------------------------------

    @Nested
    class CreateOrFindDmRoom {

        @Test
        void returns_existing_dm_when_pair_already_exists() {
            ChatRoom existing = sampleDmRoom("DM:alice,bob");

            // The service calls findDmRoom(legacyName, legacyNameReversed)
            when(chatRoomRepository.findDmRoom(anyString(), anyString()))
                    .thenReturn(List.of(existing));

            ChatRoom result = dmRoomService.createOrFindDmRoom(
                    USER_ID_1, USERNAME_1, USER_ID_2, USERNAME_2);

            assertSame(existing, result);
            verify(chatRoomRepository, never()).save(any());
        }

        @Test
        void pair_order_is_normalized() {
            // Canonical key uses lexicographic sort: alice < bob, so
            // buildCanonicalDmName always produces "DM:alice,bob"
            // regardless of argument order.

            // Both orderings should generate the same legacy-name pair
            // for the lookup query. Stub both to return empty so we
            // can inspect the save call and verify the canonical name.
            when(chatRoomRepository.findDmRoom(anyString(), anyString()))
                    .thenReturn(Collections.emptyList());
            when(chatRoomRepository.save(any(ChatRoom.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Call with (alice, bob)
            ChatRoom room1 = dmRoomService.createOrFindDmRoom(
                    USER_ID_1, USERNAME_1, USER_ID_2, USERNAME_2);

            // Call with reversed order (bob, alice)
            ChatRoom room2 = dmRoomService.createOrFindDmRoom(
                    USER_ID_2, USERNAME_2, USER_ID_1, USERNAME_1);

            // Both rooms should have the same canonical name
            assertEquals(room1.getName(), room2.getName());
            assertEquals("DM:alice,bob", room1.getName());
        }

        @Test
        void creates_new_dm_with_DIRECT_room_type_when_none_exists() {
            when(chatRoomRepository.findDmRoom(anyString(), anyString()))
                    .thenReturn(Collections.emptyList());
            when(chatRoomRepository.save(any(ChatRoom.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            ChatRoom result = dmRoomService.createOrFindDmRoom(
                    USER_ID_1, USERNAME_1, USER_ID_2, USERNAME_2);

            assertEquals(RoomType.DIRECT, result.getRoomType());
            assertEquals(2, result.getMaxParticipants());
            assertNotNull(result.getId());

            ArgumentCaptor<ChatRoom> captor = ArgumentCaptor.forClass(ChatRoom.class);
            verify(chatRoomRepository).save(captor.capture());
            assertEquals(RoomType.DIRECT, captor.getValue().getRoomType());
        }

        @Test
        void evicts_cache_after_creation() {
            when(chatRoomRepository.findDmRoom(anyString(), anyString()))
                    .thenReturn(Collections.emptyList());
            when(chatRoomRepository.save(any(ChatRoom.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            ChatRoom result = dmRoomService.createOrFindDmRoom(
                    USER_ID_1, USERNAME_1, USER_ID_2, USERNAME_2);

            verify(roomCacheEvictor).evict(result.getId());
        }
    }
}
