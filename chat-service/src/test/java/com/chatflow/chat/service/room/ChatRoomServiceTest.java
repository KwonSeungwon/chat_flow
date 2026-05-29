package com.chatflow.chat.service.room;

import com.chatflow.chat.config.RedisHealthTracker;
import com.chatflow.chat.entity.ChatRoom;
import com.chatflow.chat.entity.RoomRole;
import com.chatflow.chat.entity.RoomType;
import com.chatflow.chat.repository.ChatMessageRepository;
import com.chatflow.chat.repository.ChatRoomRepository;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ChatRoomService covering cache interactions, password handling,
 * room deletion ordering, and partial-update semantics.
 */
@ExtendWith(MockitoExtension.class)
class ChatRoomServiceTest {

    @Mock private ChatRoomRepository chatRoomRepository;
    @Mock private ChatMessageRepository chatMessageRepository;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private RedisHealthTracker redisHealth;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private RoomCacheEvictor roomCacheEvictor;
    @Mock private RoomMembershipService roomMembershipService;
    @Mock private ValueOperations<String, String> valueOps;

    private ChatRoomService chatRoomService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String ROOM_ID = "room-1";
    private static final String ROOMS_LIST_KEY = "chatflow:rooms:list";
    private static final String ROOM_CACHE_KEY = "chatflow:room:";

    @BeforeEach
    void setUp() {
        objectMapper.findAndRegisterModules();
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        chatRoomService = new ChatRoomService(
                chatRoomRepository, chatMessageRepository, redisTemplate,
                objectMapper, redisHealth, passwordEncoder, messagingTemplate,
                roomCacheEvictor, roomMembershipService);
    }

    private ChatRoom sampleRoom(String id, String name) {
        return ChatRoom.builder()
                .id(id)
                .name(name)
                .description("desc-" + id)
                .color("#6366f1")
                .roomType(RoomType.GENERAL)
                .participantCount(0)
                .maxParticipants(10)
                .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                .build();
    }

    // ── GetAllRooms ───────────────────────────────────────────────

    @Nested
    class GetAllRooms {

        @Test
        void returns_cached_list_when_redis_hit() throws Exception {
            List<ChatRoom> rooms = List.of(sampleRoom("r1", "Room 1"), sampleRoom("r2", "Room 2"));
            String json = objectMapper.writeValueAsString(rooms);

            when(redisHealth.isCircuitOpen()).thenReturn(false);
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(ROOMS_LIST_KEY)).thenReturn(json);

            List<ChatRoom> result = chatRoomService.getAllRooms();

            assertEquals(2, result.size());
            assertEquals("r1", result.get(0).getId());
            assertEquals("r2", result.get(1).getId());
            verify(chatRoomRepository, never()).findAllOrderByLastActivity();
        }

        @Test
        void falls_back_to_repository_on_cache_miss_and_caches_result() {
            List<ChatRoom> rooms = List.of(sampleRoom("r1", "Room 1"), sampleRoom("r2", "Room 2"));

            when(redisHealth.isCircuitOpen()).thenReturn(false);
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(ROOMS_LIST_KEY)).thenReturn(null);
            when(chatRoomRepository.findAllOrderByLastActivity()).thenReturn(rooms);

            List<ChatRoom> result = chatRoomService.getAllRooms();

            assertEquals(2, result.size());
            verify(chatRoomRepository, times(1)).findAllOrderByLastActivity();

            // Verify the cache set call with 30s TTL
            verify(valueOps).set(eq(ROOMS_LIST_KEY), any(String.class), eq(Duration.ofSeconds(30)));
        }

        @Test
        void skips_cache_when_circuit_open() {
            List<ChatRoom> rooms = List.of(sampleRoom("r1", "Room 1"));

            when(redisHealth.isCircuitOpen()).thenReturn(true);
            when(chatRoomRepository.findAllOrderByLastActivity()).thenReturn(rooms);

            List<ChatRoom> result = chatRoomService.getAllRooms();

            assertEquals(1, result.size());
            verify(chatRoomRepository).findAllOrderByLastActivity();
            verify(valueOps, never()).get(any());
        }
    }

    // ── GetRoom ───────────────────────────────────────────────────

    @Nested
    class GetRoom {

        @Test
        void returns_cached_when_redis_hit() throws Exception {
            ChatRoom room = sampleRoom(ROOM_ID, "Cached Room");
            String json = objectMapper.writeValueAsString(room);

            when(redisHealth.isCircuitOpen()).thenReturn(false);
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(ROOM_CACHE_KEY + ROOM_ID)).thenReturn(json);

            Optional<ChatRoom> result = chatRoomService.getRoom(ROOM_ID);

            assertTrue(result.isPresent());
            assertEquals(ROOM_ID, result.get().getId());
            verify(chatRoomRepository, never()).findById(anyString());
        }

        @Test
        void falls_back_to_repo_and_caches_with_5min_ttl() {
            ChatRoom room = sampleRoom(ROOM_ID, "DB Room");

            when(redisHealth.isCircuitOpen()).thenReturn(false);
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(ROOM_CACHE_KEY + ROOM_ID)).thenReturn(null);
            when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));

            Optional<ChatRoom> result = chatRoomService.getRoom(ROOM_ID);

            assertTrue(result.isPresent());
            verify(chatRoomRepository, times(1)).findById(ROOM_ID);

            // Capture the cache set call and assert 5-minute TTL
            ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
            verify(valueOps).set(eq(ROOM_CACHE_KEY + ROOM_ID), any(String.class), ttlCaptor.capture());
            assertEquals(Duration.ofMinutes(5), ttlCaptor.getValue());
        }
    }

    // ── CreateRoom ────────────────────────────────────────────────

    @Nested
    class CreateRoom {

        @Test
        void seeds_creator_as_owner_via_membership_service_and_evicts_cache() {
            ChatRoom request = ChatRoom.builder()
                    .name("New Room")
                    .description("A new room")
                    .build();

            when(chatRoomRepository.save(any(ChatRoom.class))).thenAnswer(inv -> inv.getArgument(0));

            ChatRoom created = chatRoomService.createRoom(request, "creator-1", "creatorUser");

            // Verify membership seeded with OWNER role
            verify(roomMembershipService).addMemberIfAbsent(
                    eq(created.getId()), eq("creator-1"), eq("creatorUser"), eq(RoomRole.OWNER));

            // Verify cache eviction
            verify(roomCacheEvictor, times(1)).evict(created.getId());
        }

        @Test
        void encrypts_password_when_present() {
            ChatRoom request = ChatRoom.builder()
                    .name("Private Room")
                    .password("pw")
                    .build();

            when(passwordEncoder.encode("pw")).thenReturn("$2a$10$encoded");
            when(chatRoomRepository.save(any(ChatRoom.class))).thenAnswer(inv -> inv.getArgument(0));

            chatRoomService.createRoom(request, "creator-1", "creatorUser");

            // Capture the saved room and verify the password was encoded
            ArgumentCaptor<ChatRoom> roomCaptor = ArgumentCaptor.forClass(ChatRoom.class);
            verify(chatRoomRepository).save(roomCaptor.capture());
            assertEquals("$2a$10$encoded", roomCaptor.getValue().getPassword());
        }
    }

    // ── VerifyRoomPassword ────────────────────────────────────────

    @Nested
    class VerifyRoomPassword {

        @Test
        void accepts_bcrypt_hash_when_matches() {
            ChatRoom room = sampleRoom(ROOM_ID, "Locked Room");
            room.setPassword("$2a$10$hashedValue");

            when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
            when(passwordEncoder.matches("pw", "$2a$10$hashedValue")).thenReturn(true);

            boolean result = chatRoomService.verifyRoomPassword(ROOM_ID, "pw");

            assertTrue(result);
        }

        @Test
        void rehashes_legacy_plaintext_on_match() {
            ChatRoom room = sampleRoom(ROOM_ID, "Legacy Room");
            room.setPassword("pw");

            when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
            when(passwordEncoder.encode("pw")).thenReturn("$2a$10$rehashed");

            boolean result = chatRoomService.verifyRoomPassword(ROOM_ID, "pw");

            assertTrue(result);

            // Verify save was called with the rehashed password
            ArgumentCaptor<ChatRoom> roomCaptor = ArgumentCaptor.forClass(ChatRoom.class);
            verify(chatRoomRepository).save(roomCaptor.capture());
            assertEquals("$2a$10$rehashed", roomCaptor.getValue().getPassword());

            // Verify cache eviction
            verify(roomCacheEvictor).evict(ROOM_ID);
        }

        @Test
        void returns_false_on_mismatch() {
            ChatRoom room = sampleRoom(ROOM_ID, "Locked Room");
            room.setPassword("$2a$10$hashedValue");

            when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
            when(passwordEncoder.matches("wrong", "$2a$10$hashedValue")).thenReturn(false);

            boolean result = chatRoomService.verifyRoomPassword(ROOM_ID, "wrong");

            assertFalse(result);
            verify(chatRoomRepository, never()).save(any());
        }
    }

    // ── DeleteRoom ────────────────────────────────────────────────

    @Nested
    class DeleteRoom {

        @Test
        void broadcasts_room_deleted_then_clears_data_and_cache() {
            when(redisHealth.isCircuitOpen()).thenReturn(false);
            when(redisTemplate.delete(anyString())).thenReturn(true);

            chatRoomService.deleteRoom(ROOM_ID);

            // Verify ordering: broadcast BEFORE data deletion
            InOrder inOrder = inOrder(messagingTemplate, chatMessageRepository,
                    chatRoomRepository, roomCacheEvictor);

            inOrder.verify(messagingTemplate).convertAndSend(
                    eq("/topic/chat/" + ROOM_ID), any(Map.class));
            inOrder.verify(chatMessageRepository).deleteAllByChatRoomId(ROOM_ID);
            inOrder.verify(chatRoomRepository).deleteById(ROOM_ID);
            inOrder.verify(roomCacheEvictor).evict(ROOM_ID);
        }
    }

    // ── UpdateRoomSettings ────────────────────────────────────────

    @Nested
    class UpdateRoomSettings {

        @Test
        void updates_only_provided_fields_and_evicts_cache() {
            ChatRoom room = sampleRoom(ROOM_ID, "Original Name");
            room.setDescription("Original Description");

            when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
            when(chatRoomRepository.save(any(ChatRoom.class))).thenAnswer(inv -> inv.getArgument(0));

            boolean result = chatRoomService.updateRoomSettings(ROOM_ID, "x", null);

            assertTrue(result);

            // Capture saved room — name updated, description unchanged
            ArgumentCaptor<ChatRoom> roomCaptor = ArgumentCaptor.forClass(ChatRoom.class);
            verify(chatRoomRepository).save(roomCaptor.capture());

            ChatRoom saved = roomCaptor.getValue();
            assertEquals("x", saved.getName());
            assertEquals("Original Description", saved.getDescription());

            // Verify cache eviction
            verify(roomCacheEvictor).evict(ROOM_ID);
        }
    }
}
