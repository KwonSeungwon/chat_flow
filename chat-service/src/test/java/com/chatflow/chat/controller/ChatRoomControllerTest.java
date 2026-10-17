package com.chatflow.chat.controller;

import com.chatflow.chat.auth.AuthInterceptor;
import com.chatflow.chat.auth.AuthenticatedUserResolver;
import com.chatflow.chat.auth.TestAuthFilter;
import com.chatflow.chat.entity.ChatRoom;
import com.chatflow.chat.entity.RoomRole;
import com.chatflow.chat.entity.RoomType;
import com.chatflow.chat.exception.ForbiddenException;
import com.chatflow.chat.exception.GlobalExceptionHandler;
import com.chatflow.chat.exception.PermissionDeniedException;
import com.chatflow.chat.mapper.ChatRoomMapper;
import com.chatflow.chat.service.RoomPermissionService;
import com.chatflow.chat.service.room.ChatRoomService;
import com.chatflow.chat.service.room.DmRoomService;
import com.chatflow.chat.service.room.RoomAccessPolicy;
import com.chatflow.chat.service.room.RoomMembershipService;
import com.chatflow.chat.service.room.RoomVisibilityService;
import com.chatflow.common.dto.ChatRoomResponse;
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
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
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
    @Mock private DmRoomService dmRoomService;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private RoomVisibilityService roomVisibilityService;
    @Mock private ChatRoomMapper chatRoomMapper;
    @Mock private RoomMembershipGuard membershipGuard;
    @Mock private RoomPermissionService roomPermissionService;
    @Mock private RoomAccessPolicy roomAccessPolicy;
    @Mock private com.chatflow.chat.service.room.UserDirectory userDirectory;

    @InjectMocks
    private ChatRoomController controller;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticatedUserResolver())
                .addFilters(TestAuthFilter.fromUserIdHeader())
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

    private static ChatRoomResponse roomResponse(String id, String name, String type, String createdBy) {
        return ChatRoomResponse.builder()
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
        void anonymous_gets_only_policy_visible_rooms() throws Exception {
            ChatRoom publicRoom = room("r1", "Room A", RoomType.GENERAL, "u1");
            ChatRoom dm = room("r2", "DM", RoomType.DIRECT, null);
            when(chatRoomService.getAllRooms()).thenReturn(List.of(publicRoom, dm));
            when(roomAccessPolicy.isVisible(publicRoom, null)).thenReturn(true);
            when(roomAccessPolicy.isVisible(dm, null)).thenReturn(false);
            when(chatRoomMapper.toResponseList(List.of(publicRoom))).thenReturn(List.of(
                    roomResponse("r1", "Room A", "GENERAL", "u1")));

            mockMvc.perform(get("/api/chat/rooms"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value("r1"));
        }

        @Test
        void authenticated_user_gets_visible_rooms_query_not_global_list() throws Exception {
            List<ChatRoom> rooms = List.of(room("r1", "Mine", RoomType.DIRECT, null));
            when(chatRoomService.getVisibleRooms("user-1")).thenReturn(rooms);
            when(roomVisibilityService.getHiddenMap("user-1")).thenReturn(Map.of());
            when(chatRoomMapper.toResponseList(rooms)).thenReturn(List.of(
                    roomResponse("r1", "Mine", "DIRECT", null)));

            mockMvc.perform(get("/api/chat/rooms").header("X-User-Id", "user-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1));

            verify(chatRoomService, never()).getAllRooms();
        }

        @SuppressWarnings("unchecked")
        @Test
        void filters_hidden_rooms_when_userId_present_and_visibility_returns_subset() throws Exception {
            ChatRoom visible = room("r1", "Visible", RoomType.DIRECT, "u1");
            ChatRoom hidden = room("r2", "Hidden", RoomType.DIRECT, "u1");
            List<ChatRoom> rooms = List.of(visible, hidden);

            when(chatRoomService.getVisibleRooms("user-1")).thenReturn(rooms);
            when(chatRoomMapper.toResponseList(any(List.class))).thenReturn(List.of(
                    roomResponse("r1", "Visible", "DIRECT", "u1")));

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
            ChatRoom entity = room("r1", "Test", RoomType.GENERAL, "user-1");
            doNothing().when(membershipGuard).requireMember("r1", "user-1");
            when(chatRoomService.getRoom("r1")).thenReturn(Optional.of(entity));
            when(chatRoomMapper.toResponse(entity))
                    .thenReturn(roomResponse("r1", "Test", "GENERAL", "user-1"));

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
            when(chatRoomMapper.toResponse(saved))
                    .thenReturn(roomResponse("r-new", "New Room", "GENERAL", "user-1"));

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

        @Test
        @DisplayName("missing name -> 400 VALIDATION_ERROR (was 500 with entity binding)")
        void returns_400_when_name_missing() throws Exception {
            String body = objectMapper.writeValueAsString(
                    Map.of("description", "no name"));

            mockMvc.perform(post("/api/chat/rooms")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("X-User-Id", "user-1")
                            .header("X-Username", "alice"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.fieldErrors.name").exists());

            verify(chatRoomService, never()).createRoom(any(), anyString(), anyString());
        }

        @Test
        @DisplayName("blank name -> 400 VALIDATION_ERROR")
        void returns_400_when_name_blank() throws Exception {
            String body = objectMapper.writeValueAsString(
                    Map.of("name", "   "));

            mockMvc.perform(post("/api/chat/rooms")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("X-User-Id", "user-1"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.fieldErrors.name").exists());
        }

        @Test
        @DisplayName("createRoom whitelists only client-settable fields")
        void creates_room_with_whitelisted_fields_only() throws Exception {
            ChatRoom saved = room("r-new", "Test", RoomType.GENERAL, "user-1");
            when(chatRoomService.createRoom(any(ChatRoom.class), eq("user-1"), eq("alice")))
                    .thenReturn(saved);
            when(chatRoomMapper.toResponse(saved))
                    .thenReturn(roomResponse("r-new", "Test", "GENERAL", "user-1"));

            // Attempt to mass-assign id, participantCount, createdAt — record ignores them
            String body = objectMapper.writeValueAsString(Map.of(
                    "name", "Test",
                    "id", "injected-id",
                    "participantCount", 999));

            mockMvc.perform(post("/api/chat/rooms")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("X-User-Id", "user-1")
                            .header("X-Username", "alice"))
                    .andExpect(status().isCreated());

            // The service receives a ChatRoom with id==null (not injected),
            // because the record has no id field — mass-assignment blocked.
            verify(chatRoomService).createRoom(argThat(room ->
                    room.getId() == null && "Test".equals(room.getName())
            ), eq("user-1"), eq("alice"));
        }

        @Test
        @DisplayName("old client roomType HANDOFF + allowedRoles -> 201 GENERAL room, allowedRoles ignored")
        void legacy_handoff_request_creates_general_room() throws Exception {
            ChatRoom saved = room("r-new", "Ward", RoomType.GENERAL, "user-1");
            when(chatRoomService.createRoom(any(ChatRoom.class), eq("user-1"), eq("alice")))
                    .thenReturn(saved);
            when(chatRoomMapper.toResponse(saved))
                    .thenReturn(roomResponse("r-new", "Ward", "GENERAL", "user-1"));

            String body = objectMapper.writeValueAsString(Map.of(
                    "name", "Ward",
                    "roomType", "HANDOFF",
                    "allowedRoles", "NURSE,DOCTOR"));

            mockMvc.perform(post("/api/chat/rooms")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("X-User-Id", "user-1")
                            .header("X-Username", "alice"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.roomType").value("GENERAL"));

            verify(chatRoomService).createRoom(argThat(room ->
                    room.getRoomType() == RoomType.GENERAL
            ), eq("user-1"), eq("alice"));
        }
    }

    // ── DeleteRoom ──────────────────────────────────────────────

    @Nested
    @DisplayName("DELETE /api/chat/rooms/{id}")
    class DeleteRoom {

        @Test
        void returns_403_when_user_does_not_have_OWNER_role() throws Exception {
            when(chatRoomService.getRoom("r1")).thenReturn(
                    Optional.of(room("r1", "Owner Room", RoomType.GENERAL, "owner-1")));
            doThrow(new PermissionDeniedException("권한이 부족합니다."))
                    .when(roomPermissionService).requireRole("r1", "not-owner", RoomRole.OWNER);

            mockMvc.perform(delete("/api/chat/rooms/r1")
                            .header("X-User-Id", "not-owner"))
                    .andExpect(status().isForbidden());
        }

        @Test
        void returns_200_when_user_has_OWNER_role() throws Exception {
            when(chatRoomService.getRoom("r1")).thenReturn(
                    Optional.of(room("r1", "My Room", RoomType.GENERAL, "owner-1")));
            doNothing().when(roomPermissionService).requireRole("r1", "owner-1", RoomRole.OWNER);

            mockMvc.perform(delete("/api/chat/rooms/r1")
                            .header("X-User-Id", "owner-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            verify(chatRoomService).deleteRoom("r1");
        }

        @Test
        @DisplayName("post-transfer: new OWNER (not createdBy) CAN delete")
        void returns_200_for_new_owner_after_transfer() throws Exception {
            // Room was created by userA, but ownership transferred to userB.
            // createdBy is still "userA", but userB has OWNER role.
            when(chatRoomService.getRoom("r1")).thenReturn(
                    Optional.of(room("r1", "Transferred Room", RoomType.GENERAL, "userA")));
            doNothing().when(roomPermissionService).requireRole("r1", "userB", RoomRole.OWNER);

            mockMvc.perform(delete("/api/chat/rooms/r1")
                            .header("X-User-Id", "userB"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            verify(chatRoomService).deleteRoom("r1");
        }

        @Test
        @DisplayName("post-transfer: demoted ex-owner (createdBy but MODERATOR) CANNOT delete")
        void returns_403_for_demoted_ex_owner() throws Exception {
            // userA created the room (createdBy=userA), but was demoted to MODERATOR.
            when(chatRoomService.getRoom("r1")).thenReturn(
                    Optional.of(room("r1", "Transferred Room", RoomType.GENERAL, "userA")));
            doThrow(new PermissionDeniedException("권한이 부족합니다."))
                    .when(roomPermissionService).requireRole("r1", "userA", RoomRole.OWNER);

            mockMvc.perform(delete("/api/chat/rooms/r1")
                            .header("X-User-Id", "userA"))
                    .andExpect(status().isForbidden());

            verify(chatRoomService, never()).deleteRoom(anyString());
        }

        @Test
        void returns_404_when_room_not_found() throws Exception {
            when(chatRoomService.getRoom("r-gone")).thenReturn(Optional.empty());

            mockMvc.perform(delete("/api/chat/rooms/r-gone")
                            .header("X-User-Id", "any-user"))
                    .andExpect(status().isNotFound());
        }
    }

    // ── UpdateRoomSettings ──────────────────────────────────────

    @Nested
    @DisplayName("PUT /api/chat/rooms/{roomId}/settings")
    class UpdateRoomSettings {

        @Test
        void returns_403_when_user_does_not_have_OWNER_role() throws Exception {
            when(chatRoomService.getRoom("r1")).thenReturn(
                    Optional.of(room("r1", "Original", RoomType.GENERAL, "owner-1")));
            doThrow(new PermissionDeniedException("권한이 부족합니다."))
                    .when(roomPermissionService).requireRole("r1", "intruder", RoomRole.OWNER);

            String body = objectMapper.writeValueAsString(Map.of("name", "Renamed"));

            mockMvc.perform(put("/api/chat/rooms/r1/settings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("X-User-Id", "intruder"))
                    .andExpect(status().isForbidden());
        }

        @Test
        void returns_200_when_user_has_OWNER_role() throws Exception {
            when(chatRoomService.getRoom("r1")).thenReturn(
                    Optional.of(room("r1", "Original", RoomType.GENERAL, "owner-1")));
            doNothing().when(roomPermissionService).requireRole("r1", "owner-1", RoomRole.OWNER);
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

        @Test
        @DisplayName("post-transfer: new OWNER (not createdBy) CAN update settings")
        void returns_200_for_new_owner_after_transfer() throws Exception {
            // Room created by userA, ownership transferred to userB.
            when(chatRoomService.getRoom("r1")).thenReturn(
                    Optional.of(room("r1", "Original", RoomType.GENERAL, "userA")));
            doNothing().when(roomPermissionService).requireRole("r1", "userB", RoomRole.OWNER);
            when(chatRoomService.updateRoomSettings("r1", "New Name", null)).thenReturn(true);

            String body = objectMapper.writeValueAsString(Map.of("name", "New Name"));

            mockMvc.perform(put("/api/chat/rooms/r1/settings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("X-User-Id", "userB"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }

        @Test
        @DisplayName("post-transfer: demoted ex-owner (createdBy but MODERATOR) CANNOT update settings")
        void returns_403_for_demoted_ex_owner() throws Exception {
            // userA created the room but was demoted to MODERATOR.
            when(chatRoomService.getRoom("r1")).thenReturn(
                    Optional.of(room("r1", "Original", RoomType.GENERAL, "userA")));
            doThrow(new PermissionDeniedException("권한이 부족합니다."))
                    .when(roomPermissionService).requireRole("r1", "userA", RoomRole.OWNER);

            String body = objectMapper.writeValueAsString(Map.of("name", "Hijacked"));

            mockMvc.perform(put("/api/chat/rooms/r1/settings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("X-User-Id", "userA"))
                    .andExpect(status().isForbidden());

            verify(chatRoomService, never()).updateRoomSettings(anyString(), anyString(), anyString());
        }

        @Test
        void returns_404_when_room_not_found() throws Exception {
            when(chatRoomService.getRoom("r-gone")).thenReturn(Optional.empty());

            String body = objectMapper.writeValueAsString(Map.of("name", "X"));

            mockMvc.perform(put("/api/chat/rooms/r-gone/settings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("X-User-Id", "any-user"))
                    .andExpect(status().isNotFound());
        }
    }

    // ── CreateDm ─────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/chat/rooms/dm")
    class CreateDm {

        @Test
        void returns_400_when_targetUserId_missing() throws Exception {
            String body = objectMapper.writeValueAsString(
                    Map.of("targetUsername", "bob"));

            mockMvc.perform(post("/api/chat/rooms/dm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("X-User-Id", "user-1"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.fieldErrors.targetUserId").exists());

            verify(dmRoomService, never()).createOrFindDmRoom(anyString(), anyString(), anyString(), anyString());
        }

        @Test
        void returns_400_when_targetUsername_missing() throws Exception {
            String body = objectMapper.writeValueAsString(
                    Map.of("targetUserId", "u2"));

            mockMvc.perform(post("/api/chat/rooms/dm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("X-User-Id", "user-1"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.fieldErrors.targetUsername").exists());
        }

        @Test
        void uses_the_server_side_username_of_targetUserId_not_the_client_one() throws Exception {
            ChatRoom dm = room("r-dm", "DM", RoomType.DIRECT, "user-1");
            when(userDirectory.resolveUsername("u2", "spoofed")).thenReturn("bob");
            when(dmRoomService.createOrFindDmRoom("user-1", "alice", "u2", "bob")).thenReturn(dm);
            when(roomMembershipService.grantMembership(eq("r-dm"), anyString(), anyString(),
                    eq(RoomAccessPolicy.GrantPath.DM_CREATE))).thenReturn(new RoomAccessPolicy.JoinDecision(null));
            when(chatRoomMapper.toResponse(dm))
                    .thenReturn(roomResponse("r-dm", "DM", "DIRECT", "user-1"));

            mockMvc.perform(post("/api/chat/rooms/dm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("targetUserId", "u2", "targetUsername", "spoofed")))
                            .header("X-User-Id", "user-1")
                            .header("X-Username", "alice"))
                    .andExpect(status().isOk());

            verify(roomMembershipService).grantMembership(
                    "r-dm", "u2", "bob", RoomAccessPolicy.GrantPath.DM_CREATE);
        }

        @Test
        void returns_400_for_a_dm_with_oneself() throws Exception {
            when(userDirectory.resolveUsername("user-1", "alice")).thenReturn("alice");
            when(dmRoomService.createOrFindDmRoom("user-1", "alice", "user-1", "alice"))
                    .thenThrow(new IllegalArgumentException("자기 자신과는 DM을 만들 수 없습니다."));

            mockMvc.perform(post("/api/chat/rooms/dm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("targetUserId", "user-1", "targetUsername", "alice")))
                            .header("X-User-Id", "user-1")
                            .header("X-Username", "alice"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void missing_X_Username_uses_the_callers_stored_username() throws Exception {
            ChatRoom dm = room("r-dm", "DM", RoomType.DIRECT, "user-1");
            when(userDirectory.resolveUsername("u2", "bob")).thenReturn("bob");
            when(userDirectory.resolveUsername("user-1", null)).thenReturn("alice");
            when(dmRoomService.createOrFindDmRoom("user-1", "alice", "u2", "bob")).thenReturn(dm);
            when(roomMembershipService.grantMembership(eq("r-dm"), anyString(), anyString(),
                    eq(RoomAccessPolicy.GrantPath.DM_CREATE))).thenReturn(new RoomAccessPolicy.JoinDecision(null));
            when(chatRoomMapper.toResponse(dm))
                    .thenReturn(roomResponse("r-dm", "DM", "DIRECT", "user-1"));

            mockMvc.perform(post("/api/chat/rooms/dm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("targetUserId", "u2", "targetUsername", "bob")))
                            .header("X-User-Id", "user-1"))
                    .andExpect(status().isOk());

            verify(roomMembershipService).grantMembership(
                    "r-dm", "user-1", "alice", RoomAccessPolicy.GrantPath.DM_CREATE);
        }

        @Test
        void missing_X_Username_and_no_stored_username_returns_400() throws Exception {
            when(userDirectory.resolveUsername("u2", "bob")).thenReturn("bob");
            when(userDirectory.resolveUsername("user-1", null)).thenReturn(null);

            mockMvc.perform(post("/api/chat/rooms/dm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("targetUserId", "u2", "targetUsername", "bob")))
                            .header("X-User-Id", "user-1"))
                    .andExpect(status().isBadRequest());

            verify(dmRoomService, never()).createOrFindDmRoom(any(), any(), any(), any());
        }

        @Test
        void returns_503_when_the_user_directory_lookup_fails() throws Exception {
            when(userDirectory.resolveUsername("u2", "bob"))
                    .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("denied"));

            mockMvc.perform(post("/api/chat/rooms/dm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("targetUserId", "u2", "targetUsername", "bob")))
                            .header("X-User-Id", "user-1")
                            .header("X-Username", "alice"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.success").value(false));

            verify(dmRoomService, never()).createOrFindDmRoom(any(), any(), any(), any());
        }

        @Test
        void returns_404_when_targetUserId_is_not_a_user() throws Exception {
            when(userDirectory.resolveUsername("ghost", "bob")).thenReturn(null);

            mockMvc.perform(post("/api/chat/rooms/dm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("targetUserId", "ghost", "targetUsername", "bob")))
                            .header("X-User-Id", "user-1")
                            .header("X-Username", "alice"))
                    .andExpect(status().isNotFound());

            verify(dmRoomService, never()).createOrFindDmRoom(any(), any(), any(), any());
        }

        @Test
        void returns_403_and_seeds_no_target_when_policy_refuses_the_caller() throws Exception {
            ChatRoom dm = room("r-dm", "DM", RoomType.DIRECT, "user-1");
            when(userDirectory.resolveUsername("u2", "bob")).thenReturn("bob");
            when(dmRoomService.createOrFindDmRoom("user-1", "alice", "u2", "bob")).thenReturn(dm);
            when(roomMembershipService.grantMembership("r-dm", "user-1", "alice",
                    RoomAccessPolicy.GrantPath.DM_CREATE))
                    .thenReturn(new RoomAccessPolicy.JoinDecision(RoomAccessPolicy.Denial.DM_NOT_PARTICIPANT));

            mockMvc.perform(post("/api/chat/rooms/dm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("targetUserId", "u2", "targetUsername", "bob")))
                            .header("X-User-Id", "user-1")
                            .header("X-Username", "alice"))
                    .andExpect(status().isForbidden());

            verify(roomMembershipService, never()).grantMembership(
                    eq("r-dm"), eq("u2"), any(), any());
        }

        @Test
        void returns_200_when_valid() throws Exception {
            ChatRoom dm = room("r-dm", "DM", RoomType.DIRECT, "user-1");
            when(userDirectory.resolveUsername("u2", "bob")).thenReturn("bob");
            when(dmRoomService.createOrFindDmRoom("user-1", "alice", "u2", "bob")).thenReturn(dm);
            when(roomMembershipService.grantMembership(eq("r-dm"), anyString(), anyString(),
                    eq(RoomAccessPolicy.GrantPath.DM_CREATE))).thenReturn(new RoomAccessPolicy.JoinDecision(null));
            when(chatRoomMapper.toResponse(dm))
                    .thenReturn(roomResponse("r-dm", "DM", "DIRECT", "user-1"));

            String body = objectMapper.writeValueAsString(
                    Map.of("targetUserId", "u2", "targetUsername", "bob"));

            mockMvc.perform(post("/api/chat/rooms/dm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("X-User-Id", "user-1")
                            .header("X-Username", "alice"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value("r-dm"));

            verify(roomMembershipService).grantMembership(
                    "r-dm", "user-1", "alice", RoomAccessPolicy.GrantPath.DM_CREATE);
            verify(roomMembershipService).grantMembership(
                    "r-dm", "u2", "bob", RoomAccessPolicy.GrantPath.DM_CREATE);
        }
    }

    // ── VerifyPassword ──────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/chat/rooms/{roomId}/verify")
    class VerifyPassword {

        private final String body = "{\"password\":\"pw\"}";
        private static final String KEY = "chatflow:room:pw-attempts:r1:user-1";

        @SuppressWarnings("unchecked")
        private void attempts(Long n) {
            when(redisTemplate.execute(any(RedisScript.class), eq(List.of(KEY)), eq("60"))).thenReturn(n);
        }

        @BeforeEach
        @SuppressWarnings("unchecked")
        void attemptCounter() {
            org.mockito.Mockito.lenient()
                    .when(redisTemplate.execute(any(RedisScript.class), any(List.class), any()))
                    .thenReturn(1L);
        }

        @Test
        void limiter_is_one_atomic_INCR_with_EXPIRE_on_first_hit() {
            assertThat(ChatRoomController.PASSWORD_ATTEMPT_SCRIPT.getScriptAsString())
                    .contains("INCR").contains("EXPIRE").contains("== 1");
        }

        @Test
        void returns_429_on_the_sixth_attempt_within_a_minute_without_checking_the_password() throws Exception {
            when(chatRoomService.getRoom("r1")).thenReturn(
                    Optional.of(room("r1", "Locked", RoomType.GENERAL, "u1")));
            attempts(6L);

            mockMvc.perform(post("/api/chat/rooms/r1/verify")
                            .contentType(MediaType.APPLICATION_JSON).content(body)
                            .header("X-User-Id", "user-1"))
                    .andExpect(status().isTooManyRequests());

            verify(chatRoomService, never()).verifyRoomPassword(anyString(), any());
        }

        @Test
        void fifth_attempt_is_still_checked() throws Exception {
            when(chatRoomService.getRoom("r1")).thenReturn(
                    Optional.of(room("r1", "Locked", RoomType.GENERAL, "u1")));
            attempts(5L);
            when(chatRoomService.verifyRoomPassword("r1", "pw")).thenReturn(false);

            mockMvc.perform(post("/api/chat/rooms/r1/verify")
                            .contentType(MediaType.APPLICATION_JSON).content(body)
                            .header("X-User-Id", "user-1"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @SuppressWarnings("unchecked")
        void valkey_down_does_not_block_password_entry() throws Exception {
            when(chatRoomService.getRoom("r1")).thenReturn(
                    Optional.of(room("r1", "Locked", RoomType.GENERAL, "u1")));
            when(redisTemplate.execute(any(RedisScript.class), eq(List.of(KEY)), eq("60")))
                    .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"));
            when(chatRoomService.verifyRoomPassword("r1", "pw")).thenReturn(false);

            mockMvc.perform(post("/api/chat/rooms/r1/verify")
                            .contentType(MediaType.APPLICATION_JSON).content(body)
                            .header("X-User-Id", "user-1"))
                    .andExpect(status().isForbidden());
            verify(chatRoomService).verifyRoomPassword("r1", "pw");
        }

        @Test
        void returns_401_without_userId() throws Exception {
            mockMvc.perform(post("/api/chat/rooms/r1/verify")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized());
            verify(roomMembershipService, never()).grantMembership(any(), any(), any(), any());
        }

        @Test
        void returns_403_for_dm_room_without_checking_password() throws Exception {
            when(chatRoomService.getRoom("r1")).thenReturn(
                    Optional.of(room("r1", "DM", RoomType.DIRECT, null)));

            mockMvc.perform(post("/api/chat/rooms/r1/verify")
                            .contentType(MediaType.APPLICATION_JSON).content(body)
                            .header("X-User-Id", "user-1"))
                    .andExpect(status().isForbidden());

            verify(chatRoomService, never()).verifyRoomPassword(anyString(), any());
            verify(roomMembershipService, never()).grantMembership(any(), any(), any(), any());
        }

        @Test
        void returns_403_and_grants_nothing_when_password_wrong() throws Exception {
            when(chatRoomService.getRoom("r1")).thenReturn(
                    Optional.of(room("r1", "Locked", RoomType.GENERAL, "u1")));
            when(chatRoomService.verifyRoomPassword("r1", "pw")).thenReturn(false);

            mockMvc.perform(post("/api/chat/rooms/r1/verify")
                            .contentType(MediaType.APPLICATION_JSON).content(body)
                            .header("X-User-Id", "user-1"))
                    .andExpect(status().isForbidden());

            verify(roomMembershipService, never()).grantMembership(any(), any(), any(), any());
        }

        @Test
        void returns_200_and_grants_via_PASSWORD_on_success() throws Exception {
            when(chatRoomService.getRoom("r1")).thenReturn(
                    Optional.of(room("r1", "Locked", RoomType.GENERAL, "u1")));
            when(chatRoomService.verifyRoomPassword("r1", "pw")).thenReturn(true);
            when(roomMembershipService.grantMembership("r1", "user-1", "alice",
                    RoomAccessPolicy.GrantPath.PASSWORD)).thenReturn(new RoomAccessPolicy.JoinDecision(null));

            mockMvc.perform(post("/api/chat/rooms/r1/verify")
                            .contentType(MediaType.APPLICATION_JSON).content(body)
                            .header("X-User-Id", "user-1")
                            .header("X-Username", "alice"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").value(true));
        }

        @Test
        void returns_403_when_policy_refuses_grant_e_g_banned() throws Exception {
            when(chatRoomService.getRoom("r1")).thenReturn(
                    Optional.of(room("r1", "Locked", RoomType.GENERAL, "u1")));
            when(chatRoomService.verifyRoomPassword("r1", "pw")).thenReturn(true);
            when(roomMembershipService.grantMembership("r1", "user-1", null,
                    RoomAccessPolicy.GrantPath.PASSWORD))
                    .thenReturn(new RoomAccessPolicy.JoinDecision(RoomAccessPolicy.Denial.BANNED));

            mockMvc.perform(post("/api/chat/rooms/r1/verify")
                            .contentType(MediaType.APPLICATION_JSON).content(body)
                            .header("X-User-Id", "user-1"))
                    .andExpect(status().isForbidden());
        }
    }

    // ── Participants ────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/chat/rooms/{roomId}/participants")
    class Participants {

        @Test
        void returns_401_without_userId() throws Exception {
            mockMvc.perform(get("/api/chat/rooms/r1/participants"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void returns_403_for_non_member() throws Exception {
            doThrow(new ForbiddenException("방 멤버가 아닙니다."))
                    .when(membershipGuard).requireMember("r1", "outsider");

            mockMvc.perform(get("/api/chat/rooms/r1/participants").header("X-User-Id", "outsider"))
                    .andExpect(status().isForbidden());

            verify(redisTemplate, never()).opsForSet();
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
