package com.chatflow.chat.controller;

import com.chatflow.chat.entity.RoomBanEntity;
import com.chatflow.chat.entity.RoomMemberEntity;
import com.chatflow.chat.entity.RoomRole;
import com.chatflow.chat.exception.*;
import com.chatflow.chat.repository.RoomMemberRepository;
import com.chatflow.chat.service.RoomBanService;
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
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class RoomBanControllerTest {

    private MockMvc mockMvc;

    @Mock
    private RoomBanService roomBanService;

    @Mock
    private RoomMemberRepository roomMemberRepository;

    @InjectMocks
    private RoomBanController controller;

    private static final String ROOM_ID = "room-1";
    private static final String CALLER_ID = "caller-1";
    private static final String TARGET_ID = "target-1";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ── GET /bans ───────────────────────────────────────────────

    @Nested
    class ListBansTests {

        @Test
        void listBans_returnsOk() throws Exception {
            RoomBanEntity ban = RoomBanEntity.builder()
                    .roomId(ROOM_ID)
                    .userId(TARGET_ID)
                    .bannedBy(CALLER_ID)
                    .reason("spam")
                    .bannedAt(LocalDateTime.of(2026, 4, 27, 12, 0))
                    .build();
            when(roomBanService.listBans(ROOM_ID, CALLER_ID)).thenReturn(List.of(ban));

            RoomMemberEntity callerMember = RoomMemberEntity.builder()
                    .roomId(ROOM_ID).userId(CALLER_ID).username("alice")
                    .role(RoomRole.OWNER).joinedAt(LocalDateTime.now()).build();
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, CALLER_ID))
                    .thenReturn(Optional.of(callerMember));

            mockMvc.perform(get("/api/chat/rooms/{roomId}/bans", ROOM_ID)
                            .header("X-User-Id", CALLER_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].userId").value(TARGET_ID))
                    .andExpect(jsonPath("$.data[0].bannedBy").value("alice"))
                    .andExpect(jsonPath("$.data[0].reason").value("spam"));
        }

        @Test
        void listBans_permissionDenied_returns403() throws Exception {
            doThrow(new PermissionDeniedException("Members cannot view bans"))
                    .when(roomBanService).listBans(anyString(), anyString());

            mockMvc.perform(get("/api/chat/rooms/{roomId}/bans", ROOM_ID)
                            .header("X-User-Id", CALLER_ID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }
    }

    // ── POST /bans ──────────────────────────────────────────────

    @Nested
    class BanUserTests {

        @Test
        void banUser_returns201() throws Exception {
            RoomBanEntity ban = RoomBanEntity.builder()
                    .roomId(ROOM_ID)
                    .userId(TARGET_ID)
                    .bannedBy(CALLER_ID)
                    .reason("harassment")
                    .bannedAt(LocalDateTime.of(2026, 4, 27, 12, 0))
                    .build();
            when(roomBanService.listBans(ROOM_ID, CALLER_ID)).thenReturn(List.of(ban));

            RoomMemberEntity callerMember = RoomMemberEntity.builder()
                    .roomId(ROOM_ID).userId(CALLER_ID).username("alice")
                    .role(RoomRole.OWNER).joinedAt(LocalDateTime.now()).build();
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, CALLER_ID))
                    .thenReturn(Optional.of(callerMember));

            mockMvc.perform(post("/api/chat/rooms/{roomId}/bans", ROOM_ID)
                            .header("X-User-Id", CALLER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\":\"" + TARGET_ID + "\",\"reason\":\"harassment\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.userId").value(TARGET_ID));

            verify(roomBanService).banUser(ROOM_ID, CALLER_ID, TARGET_ID, "harassment");
        }

        @Test
        void banUser_missingUserId_returns400() throws Exception {
            mockMvc.perform(post("/api/chat/rooms/{roomId}/bans", ROOM_ID)
                            .header("X-User-Id", CALLER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"spam\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void banUser_permissionDenied_returns403() throws Exception {
            doThrow(new PermissionDeniedException("Members cannot ban"))
                    .when(roomBanService).banUser(anyString(), anyString(), anyString(), any());

            mockMvc.perform(post("/api/chat/rooms/{roomId}/bans", ROOM_ID)
                            .header("X-User-Id", CALLER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\":\"" + TARGET_ID + "\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }

        @Test
        void banUser_dmRoom_returns400() throws Exception {
            doThrow(new RoomTypeNotSupportedException("DM room"))
                    .when(roomBanService).banUser(anyString(), anyString(), anyString(), any());

            mockMvc.perform(post("/api/chat/rooms/{roomId}/bans", ROOM_ID)
                            .header("X-User-Id", CALLER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\":\"" + TARGET_ID + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ROOM_TYPE_NOT_SUPPORTED"));
        }

        @Test
        void banUser_selfTarget_returns400() throws Exception {
            doThrow(new SelfTargetNotAllowedException("Cannot self-ban"))
                    .when(roomBanService).banUser(anyString(), anyString(), anyString(), any());

            mockMvc.perform(post("/api/chat/rooms/{roomId}/bans", ROOM_ID)
                            .header("X-User-Id", CALLER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\":\"" + CALLER_ID + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("SELF_TARGET_NOT_ALLOWED"));
        }
    }

    // ── DELETE /bans/{userId} ───────────────────────────────────

    @Nested
    class UnbanUserTests {

        @Test
        void unbanUser_returns204() throws Exception {
            mockMvc.perform(delete("/api/chat/rooms/{roomId}/bans/{userId}", ROOM_ID, TARGET_ID)
                            .header("X-User-Id", CALLER_ID))
                    .andExpect(status().isNoContent());

            verify(roomBanService).unbanUser(ROOM_ID, CALLER_ID, TARGET_ID);
        }

        @Test
        void unbanUser_permissionDenied_returns403() throws Exception {
            doThrow(new PermissionDeniedException("Members cannot unban"))
                    .when(roomBanService).unbanUser(anyString(), anyString(), anyString());

            mockMvc.perform(delete("/api/chat/rooms/{roomId}/bans/{userId}", ROOM_ID, TARGET_ID)
                            .header("X-User-Id", CALLER_ID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }
    }
}
