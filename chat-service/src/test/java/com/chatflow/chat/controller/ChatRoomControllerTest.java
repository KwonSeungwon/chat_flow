package com.chatflow.chat.controller;

import com.chatflow.chat.auth.AuthInterceptor;
import com.chatflow.chat.auth.AuthenticatedUserResolver;
import com.chatflow.chat.entity.ChatRoom;
import com.chatflow.chat.entity.RoomType;
import com.chatflow.chat.exception.ForbiddenException;
import com.chatflow.chat.exception.GlobalExceptionHandler;
import com.chatflow.chat.service.AuditService;
import com.chatflow.chat.service.ChatRoomService;
import com.chatflow.chat.service.DmRoomService;
import com.chatflow.chat.service.MessageReadService;
import com.chatflow.chat.service.MessageSenderService;
import com.chatflow.chat.service.RoomMembershipService;
import com.chatflow.chat.service.RoomVisibilityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ChatRoomControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock private ChatRoomService chatRoomService;
    @Mock private RoomMembershipService roomMembershipService;
    @Mock private MessageReadService messageReadService;
    @Mock private DmRoomService dmRoomService;
    @Mock private AuditService auditService;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private RoomVisibilityService roomVisibilityService;
    @Mock private MessageSenderService messageSenderService;
    @Mock private RoomMembershipGuard membershipGuard;

    @InjectMocks
    private ChatRoomController controller;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticatedUserResolver())
                .addInterceptors(new AuthInterceptor(membershipGuard))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ── helpers ──────────────────────────────────────────────────

    private static ChatRoom room(String id, String name, RoomType type, String createdBy) {
        return ChatRoom.builder()
                .id(id)
                .name(name)
                .roomType(type)
                .createdBy(createdBy)
                .createdAt(LocalDateTime.of(2026, 5, 1, 12, 0))
                .build();
    }

    // ── GetAllRooms ─────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/chat/rooms")
    class GetAllRooms {

        @Test
        void returns_all_rooms_when_no_userId_header() throws Exception {
            List<ChatRoom> rooms = List.of(
                    room("r1", "Room A", RoomType.GENERAL, "u1"),
                    room("r2", "Room B", RoomType.GENERAL, "u2"));
            when(chatRoomService.getAllRooms()).thenReturn(rooms);

            mockMvc.perform(get("/api/chat/rooms"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.length()").value(2));
        }

        @Test
        void filters_hidden_rooms_when_userId_present_and_visibility_returns_subset() throws Exception {
            ChatRoom visible = room("r1", "Visible", RoomType.DIRECT, "u1");
            ChatRoom hidden = room("r2", "Hidden", RoomType.DIRECT, "u1");
            List<ChatRoom> rooms = List.of(visible, hidden);

            when(chatRoomService.getAllRooms()).thenReturn(rooms);

            Map<String, Instant> hiddenMap = Map.of("r2", Instant.now());
            when(roomVisibilityService.getHiddenMap("user-1")).thenReturn(hiddenMap);
            when(roomVisibilityService.isVisible(eq(visible), eq(hiddenMap))).thenReturn(true);
            when(roomVisibilityService.isVisible(eq(hidden), eq(hiddenMap))).thenReturn(false);

            mockMvc.perform(get("/api/chat/rooms")
                            .header("X-User-Id", "user-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value("r1"));
        }
    }

    // ── GetRoom ─────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/chat/rooms/{id}")
    class GetRoom {

        @Test
        void returns_200_when_room_exists() throws Exception {
            doNothing().when(membershipGuard).requireMember("r1", "user-1");
            when(chatRoomService.getRoom("r1"))
                    .thenReturn(Optional.of(room("r1", "Test", RoomType.GENERAL, "user-1")));

            mockMvc.perform(get("/api/chat/rooms/r1")
                            .header("X-User-Id", "user-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value("r1"));
        }

        @Test
        void returns_401_when_userId_header_missing() throws Exception {
            mockMvc.perform(get("/api/chat/rooms/r1"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        void returns_403_when_not_room_member() throws Exception {
            doThrow(new ForbiddenException("방 멤버가 아닙니다."))
                    .when(membershipGuard).requireMember("r1", "outsider");

            mockMvc.perform(get("/api/chat/rooms/r1")
                            .header("X-User-Id", "outsider"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false));

            verify(chatRoomService, never()).getRoom(anyString());
        }

        @Test
        void returns_404_when_room_missing() throws Exception {
            doNothing().when(membershipGuard).requireMember("r-gone", "user-1");
            when(chatRoomService.getRoom("r-gone")).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/chat/rooms/r-gone")
                            .header("X-User-Id", "user-1"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    // ── CreateRoom ──────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/chat/rooms")
    class CreateRoom {

        @Test
        void returns_401_when_X_User_Id_missing() throws Exception {
            String body = objectMapper.writeValueAsString(
                    Map.of("name", "New Room"));

            mockMvc.perform(post("/api/chat/rooms")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        void returns_201_with_created_room_when_request_valid() throws Exception {
            ChatRoom saved = room("r-new", "New Room", RoomType.GENERAL, "user-1");
            when(chatRoomService.createRoom(any(ChatRoom.class), eq("user-1"), eq("alice")))
                    .thenReturn(saved);

            String body = objectMapper.writeValueAsString(
                    Map.of("name", "New Room"));

            mockMvc.perform(post("/api/chat/rooms")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("X-User-Id", "user-1")
                            .header("X-Username", "alice"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value("r-new"));
        }
    }

    // ── DeleteRoom ──────────────────────────────────────────────

    @Nested
    @DisplayName("DELETE /api/chat/rooms/{id}")
    class DeleteRoom {

        @Test
        void returns_403_when_createdBy_does_not_match_userId() throws Exception {
            ChatRoom owned = room("r1", "Owner Room", RoomType.GENERAL, "owner-1");
            when(chatRoomService.getRoom("r1")).thenReturn(Optional.of(owned));

            mockMvc.perform(delete("/api/chat/rooms/r1")
                            .header("X-User-Id", "not-owner"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        void returns_403_when_createdBy_is_null_legacy_room() throws Exception {
            ChatRoom legacy = room("r-legacy", "Legacy", RoomType.GENERAL, null);
            when(chatRoomService.getRoom("r-legacy")).thenReturn(Optional.of(legacy));

            mockMvc.perform(delete("/api/chat/rooms/r-legacy")
                            .header("X-User-Id", "any-user"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        void returns_200_when_owner() throws Exception {
            ChatRoom owned = room("r1", "My Room", RoomType.GENERAL, "owner-1");
            when(chatRoomService.getRoom("r1")).thenReturn(Optional.of(owned));

            mockMvc.perform(delete("/api/chat/rooms/r1")
                            .header("X-User-Id", "owner-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            verify(chatRoomService).deleteRoom("r1");
        }
    }

    // ── UpdateRoomSettings ──────────────────────────────────────

    @Nested
    @DisplayName("PUT /api/chat/rooms/{roomId}/settings")
    class UpdateRoomSettings {

        @Test
        void returns_403_when_not_owner() throws Exception {
            ChatRoom owned = room("r1", "Original", RoomType.GENERAL, "owner-1");
            when(chatRoomService.getRoom("r1")).thenReturn(Optional.of(owned));

            String body = objectMapper.writeValueAsString(Map.of("name", "Renamed"));

            mockMvc.perform(put("/api/chat/rooms/r1/settings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("X-User-Id", "intruder"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        void returns_200_when_owner_updates_name_only() throws Exception {
            ChatRoom existing = room("r1", "Original", RoomType.GENERAL, "owner-1");

            when(chatRoomService.getRoom("r1")).thenReturn(Optional.of(existing));
            when(chatRoomService.updateRoomSettings("r1", "Renamed", null)).thenReturn(true);

            String body = objectMapper.writeValueAsString(Map.of("name", "Renamed"));

            mockMvc.perform(put("/api/chat/rooms/r1/settings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("X-User-Id", "owner-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data").value(true));
        }
    }

    // ── HideRoom ────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/chat/rooms/{roomId}/hide")
    class HideRoom {

        @Test
        void returns_400_when_room_is_not_DIRECT() throws Exception {
            ChatRoom general = room("r1", "General", RoomType.GENERAL, "u1");
            when(chatRoomService.getRoom("r1")).thenReturn(Optional.of(general));

            mockMvc.perform(post("/api/chat/rooms/r1/hide")
                            .header("X-User-Id", "user-1")
                            .header("X-Username", "alice"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        void returns_200_when_DIRECT_and_user_authenticated() throws Exception {
            ChatRoom dm = room("r-dm", "DM", RoomType.DIRECT, "u1");
            when(chatRoomService.getRoom("r-dm")).thenReturn(Optional.of(dm));

            mockMvc.perform(post("/api/chat/rooms/r-dm/hide")
                            .header("X-User-Id", "user-1")
                            .header("X-Username", "alice"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            verify(roomVisibilityService).hide("user-1", "r-dm");
        }
    }
}
