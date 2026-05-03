package com.chatflow.chat.controller;

import com.chatflow.chat.entity.RoomMemberEntity;
import com.chatflow.chat.entity.RoomRole;
import com.chatflow.chat.exception.*;
import com.chatflow.chat.repository.RoomMemberRepository;
import com.chatflow.chat.service.MemberManagementService;
import com.chatflow.chat.service.MuteResult;
import com.chatflow.chat.service.RoomPermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class RoomMemberManagementControllerTest {

    private MockMvc mockMvc;

    @Mock
    private RoomMemberRepository roomMemberRepository;

    @Mock
    private MemberManagementService memberManagementService;

    @Mock
    private RoomPermissionService roomPermissionService;

    @InjectMocks
    private RoomMemberManagementController controller;

    private static final String ROOM_ID = "room-1";
    private static final String CALLER_ID = "caller-1";
    private static final String TARGET_ID = "target-1";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private RoomMemberEntity member(String userId, String username, RoomRole role) {
        return RoomMemberEntity.builder()
                .roomId(ROOM_ID)
                .userId(userId)
                .username(username)
                .role(role)
                .joinedAt(LocalDateTime.now())
                .build();
    }

    // ── GET /members ────────────────────────────────────────────

    @Nested
    class GetMembersTests {

        @Test
        void getMembers_returnsOkWithMemberList() throws Exception {
            RoomMemberEntity m1 = member("u1", "alice", RoomRole.OWNER);
            RoomMemberEntity m2 = member("u2", "bob", RoomRole.MEMBER);
            when(roomMemberRepository.findByRoomId(ROOM_ID)).thenReturn(List.of(m1, m2));

            mockMvc.perform(get("/api/chat/rooms/{roomId}/members", ROOM_ID)
                            .header("X-User-Id", CALLER_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data.length()").value(2))
                    .andExpect(jsonPath("$.data[0].userId").value("u1"))
                    .andExpect(jsonPath("$.data[0].role").value("OWNER"))
                    .andExpect(jsonPath("$.data[1].userId").value("u2"))
                    .andExpect(jsonPath("$.data[1].role").value("MEMBER"));

            verify(roomPermissionService).requireRole(ROOM_ID, CALLER_ID,
                    RoomRole.OWNER, RoomRole.MODERATOR, RoomRole.MEMBER);
        }

        @Test
        void getMembers_nonMember_returns403() throws Exception {
            doThrow(new PermissionDeniedException("Not a member"))
                    .when(roomPermissionService).requireRole(eq(ROOM_ID), eq(CALLER_ID),
                            eq(RoomRole.OWNER), eq(RoomRole.MODERATOR), eq(RoomRole.MEMBER));

            mockMvc.perform(get("/api/chat/rooms/{roomId}/members", ROOM_ID)
                            .header("X-User-Id", CALLER_ID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }
    }

    // ── PATCH /members/{userId}/role ────────────────────────────

    @Nested
    class ChangeRoleTests {

        @Test
        void changeRole_toModerator_returnsOk() throws Exception {
            mockMvc.perform(patch("/api/chat/rooms/{roomId}/members/{userId}/role", ROOM_ID, TARGET_ID)
                            .header("X-User-Id", CALLER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"role\":\"MODERATOR\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            verify(memberManagementService).changeRole(ROOM_ID, CALLER_ID, TARGET_ID, RoomRole.MODERATOR);
        }

        @Test
        void changeRole_toOwner_callsTransferOwnership() throws Exception {
            mockMvc.perform(patch("/api/chat/rooms/{roomId}/members/{userId}/role", ROOM_ID, TARGET_ID)
                            .header("X-User-Id", CALLER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"role\":\"OWNER\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            verify(memberManagementService).transferOwnership(ROOM_ID, CALLER_ID, TARGET_ID);
            verify(memberManagementService, never()).changeRole(anyString(), anyString(), anyString(), any());
        }

        @Test
        void changeRole_blankRole_returns400() throws Exception {
            mockMvc.perform(patch("/api/chat/rooms/{roomId}/members/{userId}/role", ROOM_ID, TARGET_ID)
                            .header("X-User-Id", CALLER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"role\":\"\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void changeRole_invalidRoleString_returns400() throws Exception {
            mockMvc.perform(patch("/api/chat/rooms/{roomId}/members/{userId}/role", ROOM_ID, TARGET_ID)
                            .header("X-User-Id", CALLER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"role\":\"ADMIN\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void changeRole_permissionDenied_returns403() throws Exception {
            doThrow(new PermissionDeniedException("OWNER only"))
                    .when(memberManagementService).changeRole(anyString(), anyString(), anyString(), any());

            mockMvc.perform(patch("/api/chat/rooms/{roomId}/members/{userId}/role", ROOM_ID, TARGET_ID)
                            .header("X-User-Id", CALLER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"role\":\"MODERATOR\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }

        @Test
        void changeRole_dmRoom_returns400() throws Exception {
            doThrow(new RoomTypeNotSupportedException("DM room"))
                    .when(memberManagementService).changeRole(anyString(), anyString(), anyString(), any());

            mockMvc.perform(patch("/api/chat/rooms/{roomId}/members/{userId}/role", ROOM_ID, TARGET_ID)
                            .header("X-User-Id", CALLER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"role\":\"MODERATOR\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ROOM_TYPE_NOT_SUPPORTED"));
        }

        @Test
        void changeRole_selfTarget_returns400() throws Exception {
            doThrow(new SelfTargetNotAllowedException("Cannot self-target"))
                    .when(memberManagementService).changeRole(anyString(), anyString(), anyString(), any());

            mockMvc.perform(patch("/api/chat/rooms/{roomId}/members/{userId}/role", ROOM_ID, CALLER_ID)
                            .header("X-User-Id", CALLER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"role\":\"MODERATOR\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("SELF_TARGET_NOT_ALLOWED"));
        }
    }

    // ── DELETE /members/{userId} ────────────────────────────────

    @Nested
    class KickMemberTests {

        @Test
        void kickMember_returns204() throws Exception {
            mockMvc.perform(delete("/api/chat/rooms/{roomId}/members/{userId}", ROOM_ID, TARGET_ID)
                            .header("X-User-Id", CALLER_ID))
                    .andExpect(status().isNoContent());

            verify(memberManagementService).kickMember(ROOM_ID, CALLER_ID, TARGET_ID);
        }

        @Test
        void kickMember_permissionDenied_returns403() throws Exception {
            doThrow(new PermissionDeniedException("Insufficient role"))
                    .when(memberManagementService).kickMember(anyString(), anyString(), anyString());

            mockMvc.perform(delete("/api/chat/rooms/{roomId}/members/{userId}", ROOM_ID, TARGET_ID)
                            .header("X-User-Id", CALLER_ID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }

        @Test
        void kickMember_selfTarget_returns400() throws Exception {
            doThrow(new SelfTargetNotAllowedException("Cannot self-kick"))
                    .when(memberManagementService).kickMember(anyString(), anyString(), anyString());

            mockMvc.perform(delete("/api/chat/rooms/{roomId}/members/{userId}", ROOM_ID, CALLER_ID)
                            .header("X-User-Id", CALLER_ID))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("SELF_TARGET_NOT_ALLOWED"));
        }
    }

    // ── POST /members/{userId}/mute ─────────────────────────────

    @Nested
    class MuteMemberTests {

        @Test
        void muteMember_returns200WithMutedUntil() throws Exception {
            LocalDateTime mutedUntil = LocalDateTime.of(2026, 4, 27, 15, 0);
            when(memberManagementService.muteMember(ROOM_ID, CALLER_ID, TARGET_ID, 30))
                    .thenReturn(new MuteResult(mutedUntil));

            mockMvc.perform(post("/api/chat/rooms/{roomId}/members/{userId}/mute", ROOM_ID, TARGET_ID)
                            .header("X-User-Id", CALLER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"minutes\":30}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.mutedUntil").exists());
        }

        @Test
        void muteMember_invalidMinutes_returns400() throws Exception {
            doThrow(new IllegalArgumentException("Invalid minutes"))
                    .when(memberManagementService).muteMember(anyString(), anyString(), anyString(), anyInt());

            mockMvc.perform(post("/api/chat/rooms/{roomId}/members/{userId}/mute", ROOM_ID, TARGET_ID)
                            .header("X-User-Id", CALLER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"minutes\":10}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void muteMember_permissionDenied_returns403() throws Exception {
            doThrow(new PermissionDeniedException("Insufficient role"))
                    .when(memberManagementService).muteMember(anyString(), anyString(), anyString(), anyInt());

            mockMvc.perform(post("/api/chat/rooms/{roomId}/members/{userId}/mute", ROOM_ID, TARGET_ID)
                            .header("X-User-Id", CALLER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"minutes\":30}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }
    }

    // ── DELETE /members/{userId}/mute ────────────────────────────

    @Nested
    class UnmuteMemberTests {

        @Test
        void unmuteMember_returns204() throws Exception {
            mockMvc.perform(delete("/api/chat/rooms/{roomId}/members/{userId}/mute", ROOM_ID, TARGET_ID)
                            .header("X-User-Id", CALLER_ID))
                    .andExpect(status().isNoContent());

            verify(memberManagementService).unmuteMember(ROOM_ID, CALLER_ID, TARGET_ID);
        }

        @Test
        void unmuteMember_permissionDenied_returns403() throws Exception {
            doThrow(new PermissionDeniedException("Insufficient role"))
                    .when(memberManagementService).unmuteMember(anyString(), anyString(), anyString());

            mockMvc.perform(delete("/api/chat/rooms/{roomId}/members/{userId}/mute", ROOM_ID, TARGET_ID)
                            .header("X-User-Id", CALLER_ID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }
    }
}
