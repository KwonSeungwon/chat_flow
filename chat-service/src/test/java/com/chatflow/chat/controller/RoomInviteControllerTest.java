package com.chatflow.chat.controller;

import com.chatflow.chat.auth.AuthInterceptor;
import com.chatflow.chat.auth.AuthenticatedUserResolver;
import com.chatflow.chat.entity.ChatRoom;
import com.chatflow.chat.entity.RoomType;
import com.chatflow.chat.exception.ForbiddenException;
import com.chatflow.chat.exception.GlobalExceptionHandler;
import com.chatflow.chat.result.ChatErrorCode;
import com.chatflow.chat.result.Result;
import com.chatflow.chat.service.room.ChatRoomService;
import com.chatflow.chat.service.room.InviteLinkService;
import com.chatflow.chat.service.room.ParticipantService;
import com.chatflow.chat.service.room.RoomMembershipService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class RoomInviteControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock private ChatRoomService chatRoomService;
    @Mock private RoomMembershipService roomMembershipService;
    @Mock private InviteLinkService inviteLinkService;
    @Mock private ParticipantService participantService;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private SetOperations<String, String> setOperations;
    @Mock private RoomMembershipGuard membershipGuard;

    @InjectMocks
    private RoomInviteController controller;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticatedUserResolver())
                .addInterceptors(new AuthInterceptor(membershipGuard))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // -- helpers ----------------------------------------------------------

    private static ChatRoom room(String id, String name, boolean allowInvites) {
        return ChatRoom.builder()
                .id(id)
                .name(name)
                .roomType(RoomType.GENERAL)
                .allowInvites(allowInvites)
                .createdBy("creator")
                .createdAt(LocalDateTime.of(2026, 5, 1, 12, 0))
                .build();
    }

    // -- InviteUser -------------------------------------------------------

    @Nested
    @DisplayName("POST /api/chat/rooms/{roomId}/invite")
    class InviteUser {

        @Test
        void _401_when_no_userId_header() throws Exception {
            String body = objectMapper.writeValueAsString(
                    Map.of("targetUsername", "bob"));

            mockMvc.perform(post("/api/chat/rooms/r1/invite")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("X-Username", "alice"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.success").value(false));

            verify(roomMembershipService, never())
                    .sendInviteMessage(anyString(), anyString(), anyString());
        }

        @Test
        void _404_when_room_not_found() throws Exception {
            when(chatRoomService.getRoom("no-room")).thenReturn(Optional.empty());

            String body = objectMapper.writeValueAsString(
                    Map.of("targetUsername", "bob"));

            mockMvc.perform(post("/api/chat/rooms/no-room/invite")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("X-User-Id", "user-1")
                            .header("X-Username", "alice"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        void _400_when_allowInvites_is_false() throws Exception {
            when(chatRoomService.getRoom("r1")).thenReturn(Optional.of(room("r1", "Room", false)));

            String body = objectMapper.writeValueAsString(
                    Map.of("targetUsername", "bob"));

            mockMvc.perform(post("/api/chat/rooms/r1/invite")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("X-User-Id", "user-1")
                            .header("X-Username", "alice"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        void _400_when_room_full() throws Exception {
            when(chatRoomService.getRoom("r1")).thenReturn(Optional.of(room("r1", "Room", true)));
            when(participantService.isRoomFull("r1")).thenReturn(true);

            String body = objectMapper.writeValueAsString(
                    Map.of("targetUsername", "bob"));

            mockMvc.perform(post("/api/chat/rooms/r1/invite")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("X-User-Id", "user-1")
                            .header("X-Username", "alice"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        void _400_when_targetUsername_already_a_participant() throws Exception {
            when(chatRoomService.getRoom("r1")).thenReturn(Optional.of(room("r1", "Room", true)));
            when(participantService.isRoomFull("r1")).thenReturn(false);
            when(redisTemplate.opsForSet()).thenReturn(setOperations);
            when(setOperations.members("chatflow:room:participants:r1"))
                    .thenReturn(Set.of("uid-1:sess1:bob"));

            String body = objectMapper.writeValueAsString(
                    Map.of("targetUsername", "bob"));

            mockMvc.perform(post("/api/chat/rooms/r1/invite")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("X-User-Id", "user-1")
                            .header("X-Username", "alice"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));

            verify(roomMembershipService, never())
                    .sendInviteMessage(eq("r1"), eq("alice"), eq("bob"));
        }

        @Test
        @DisplayName("blank targetUsername -> 400 VALIDATION_ERROR")
        void _400_when_targetUsername_blank() throws Exception {
            String body = objectMapper.writeValueAsString(
                    Map.of("targetUsername", "   "));

            mockMvc.perform(post("/api/chat/rooms/r1/invite")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("X-User-Id", "user-1")
                            .header("X-Username", "alice"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.fieldErrors.targetUsername").exists());

            verify(roomMembershipService, never())
                    .sendInviteMessage(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("missing targetUsername -> 400 VALIDATION_ERROR")
        void _400_when_targetUsername_missing() throws Exception {
            mockMvc.perform(post("/api/chat/rooms/r1/invite")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}")
                            .header("X-User-Id", "user-1")
                            .header("X-Username", "alice"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.fieldErrors.targetUsername").exists());

            verify(roomMembershipService, never())
                    .sendInviteMessage(anyString(), anyString(), anyString());
        }

        @Test
        void _200_and_sendInviteMessage_called_on_success() throws Exception {
            when(chatRoomService.getRoom("r1")).thenReturn(Optional.of(room("r1", "Room", true)));
            when(participantService.isRoomFull("r1")).thenReturn(false);
            when(redisTemplate.opsForSet()).thenReturn(setOperations);
            when(setOperations.members("chatflow:room:participants:r1"))
                    .thenReturn(Set.of("uid-1:sess1:alice"));

            String body = objectMapper.writeValueAsString(
                    Map.of("targetUsername", "bob"));

            mockMvc.perform(post("/api/chat/rooms/r1/invite")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("X-User-Id", "user-1")
                            .header("X-Username", "alice"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            verify(roomMembershipService).sendInviteMessage("r1", "alice", "bob");
        }
    }

    // -- CreateInviteLink -------------------------------------------------

    @Nested
    @DisplayName("POST /api/chat/rooms/{roomId}/invite-link")
    class CreateInviteLink {

        @Test
        void _401_when_no_userId_header() throws Exception {
            mockMvc.perform(post("/api/chat/rooms/r1/invite-link"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        void _403_when_not_room_member() throws Exception {
            doThrow(new ForbiddenException("방 멤버가 아닙니다."))
                    .when(membershipGuard).requireMember("r1", "outsider");

            mockMvc.perform(post("/api/chat/rooms/r1/invite-link")
                            .header("X-User-Id", "outsider"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        void _403_when_allowInvites_false_even_for_member() throws Exception {
            doNothing().when(membershipGuard).requireMember("r1", "user-1");
            when(chatRoomService.getRoom("r1")).thenReturn(Optional.of(room("r1", "Room", false)));

            mockMvc.perform(post("/api/chat/rooms/r1/invite-link")
                            .header("X-User-Id", "user-1"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        void _200_with_token_and_url_on_success() throws Exception {
            doNothing().when(membershipGuard).requireMember("r1", "user-1");
            when(chatRoomService.getRoom("r1")).thenReturn(Optional.of(room("r1", "Room", true)));
            when(inviteLinkService.createInviteToken("r1")).thenReturn("tok-abc");
            when(inviteLinkService.getInviteUrl("tok-abc"))
                    .thenReturn("https://app.chatflow.ai.kr/invite/tok-abc");

            mockMvc.perform(post("/api/chat/rooms/r1/invite-link")
                            .header("X-User-Id", "user-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.token").value("tok-abc"))
                    .andExpect(jsonPath("$.data.url").value("https://app.chatflow.ai.kr/invite/tok-abc"));
        }
    }

    // -- JoinByInvite -----------------------------------------------------

    @Nested
    @DisplayName("POST /api/chat/rooms/join-by-invite")
    class JoinByInvite {

        @Test
        void _401_when_no_userId() throws Exception {
            String body = objectMapper.writeValueAsString(
                    Map.of("token", "tok-abc"));

            mockMvc.perform(post("/api/chat/rooms/join-by-invite")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("missing token -> 400 VALIDATION_ERROR")
        void _400_when_token_missing() throws Exception {
            mockMvc.perform(post("/api/chat/rooms/join-by-invite")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}")
                            .header("X-User-Id", "user-1")
                            .header("X-Username", "alice"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.fieldErrors.token").exists());
        }

        @Test
        @DisplayName("blank token -> 400 VALIDATION_ERROR")
        void _400_when_token_blank() throws Exception {
            String body = objectMapper.writeValueAsString(
                    Map.of("token", "   "));

            mockMvc.perform(post("/api/chat/rooms/join-by-invite")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("X-User-Id", "user-1")
                            .header("X-Username", "alice"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.fieldErrors.token").exists());
        }

        @Test
        void _410_when_token_expired() throws Exception {
            when(inviteLinkService.resolveToken("tok-expired"))
                    .thenReturn(Result.err(ChatErrorCode.GONE, "초대 링크가 만료되었거나 유효하지 않습니다."));

            String body = objectMapper.writeValueAsString(
                    Map.of("token", "tok-expired"));

            mockMvc.perform(post("/api/chat/rooms/join-by-invite")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("X-User-Id", "user-1")
                            .header("X-Username", "alice"))
                    .andExpect(status().isGone())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        void _400_when_room_full() throws Exception {
            when(inviteLinkService.resolveToken("tok-abc")).thenReturn(Result.ok("r1"));
            when(chatRoomService.getRoom("r1")).thenReturn(Optional.of(room("r1", "Room", true)));
            when(participantService.isRoomFull("r1")).thenReturn(true);

            String body = objectMapper.writeValueAsString(
                    Map.of("token", "tok-abc"));

            mockMvc.perform(post("/api/chat/rooms/join-by-invite")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("X-User-Id", "user-1")
                            .header("X-Username", "alice"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        void _200_and_addMemberIfAbsent_called_on_success() throws Exception {
            when(inviteLinkService.resolveToken("tok-abc")).thenReturn(Result.ok("r1"));
            when(chatRoomService.getRoom("r1")).thenReturn(Optional.of(room("r1", "Room", true)));
            when(participantService.isRoomFull("r1")).thenReturn(false);

            String body = objectMapper.writeValueAsString(
                    Map.of("token", "tok-abc"));

            mockMvc.perform(post("/api/chat/rooms/join-by-invite")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("X-User-Id", "user-1")
                            .header("X-Username", "alice"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.roomId").value("r1"))
                    .andExpect(jsonPath("$.data.roomName").value("Room"));

            verify(roomMembershipService).addMemberIfAbsent("r1", "user-1", "alice");
        }
    }
}
