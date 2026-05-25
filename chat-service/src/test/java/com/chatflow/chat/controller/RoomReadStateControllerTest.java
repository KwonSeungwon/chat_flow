package com.chatflow.chat.controller;

import com.chatflow.chat.auth.AuthInterceptor;
import com.chatflow.chat.auth.AuthenticatedUserResolver;
import com.chatflow.chat.entity.ChatRoom;
import com.chatflow.chat.entity.RoomType;
import com.chatflow.chat.exception.ForbiddenException;
import com.chatflow.chat.exception.GlobalExceptionHandler;
import com.chatflow.chat.service.ChatRoomService;
import com.chatflow.chat.service.ReadReceiptService;
import com.chatflow.chat.service.UnreadCountService;
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
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class RoomReadStateControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock private ChatRoomService chatRoomService;
    @Mock private UnreadCountService unreadCountService;
    @Mock private ReadReceiptService readReceiptService;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private RoomMembershipGuard membershipGuard;

    @InjectMocks
    private RoomReadStateController controller;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticatedUserResolver())
                .addInterceptors(new AuthInterceptor(membershipGuard))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // -- helpers ----------------------------------------------------------

    private static ChatRoom room(String id, String name) {
        return ChatRoom.builder()
                .id(id)
                .name(name)
                .roomType(RoomType.GENERAL)
                .createdBy("creator")
                .createdAt(LocalDateTime.of(2026, 5, 1, 12, 0))
                .build();
    }

    // -- UnreadCounts -----------------------------------------------------

    @Nested
    @DisplayName("GET /api/chat/rooms/unread-counts")
    class UnreadCounts {

        @Test
        void returns_200_with_empty_map_when_no_userId_header() throws Exception {
            mockMvc.perform(get("/api/chat/rooms/unread-counts"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data").isEmpty());
        }

        @Test
        void returns_200_with_per_room_counts_when_userId_present() throws Exception {
            List<ChatRoom> rooms = List.of(room("r1", "A"), room("r2", "B"));
            when(chatRoomService.getAllRooms()).thenReturn(rooms);
            when(unreadCountService.getUnreadCounts(eq("user-1"), eq(List.of("r1", "r2"))))
                    .thenReturn(Map.of("r1", 3L, "r2", 0L));

            mockMvc.perform(get("/api/chat/rooms/unread-counts")
                            .header("X-User-Id", "user-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.r1").value(3))
                    .andExpect(jsonPath("$.data.r2").value(0));
        }
    }

    // -- Readers ----------------------------------------------------------

    @Nested
    @DisplayName("GET /api/chat/rooms/{roomId}/readers")
    class Readers {

        @Test
        void returns_401_when_no_userId_header() throws Exception {
            mockMvc.perform(get("/api/chat/rooms/r1/readers"))
                    .andExpect(status().isUnauthorized());
            verify(readReceiptService, never()).getRoomReadPositions(anyString());
        }

        @Test
        void returns_403_when_not_room_member() throws Exception {
            doThrow(new ForbiddenException("방 멤버가 아닙니다."))
                    .when(membershipGuard).requireMember("r1", "outsider");

            mockMvc.perform(get("/api/chat/rooms/r1/readers")
                            .header("X-User-Id", "outsider"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false));
            verify(readReceiptService, never()).getRoomReadPositions(anyString());
        }

        @Test
        void returns_200_with_userId_to_lastReadMessageId_map_when_member() throws Exception {
            doNothing().when(membershipGuard).requireMember("r1", "user-1");
            when(readReceiptService.getRoomReadPositions("r1"))
                    .thenReturn(Map.of("user-1", "msg-42", "user-2", "msg-40"));

            mockMvc.perform(get("/api/chat/rooms/r1/readers")
                            .header("X-User-Id", "user-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.['user-1']").value("msg-42"))
                    .andExpect(jsonPath("$.data.['user-2']").value("msg-40"));
        }
    }

    // -- LastRead ---------------------------------------------------------

    @Nested
    @DisplayName("GET/PUT /api/chat/rooms/{roomId}/last-read")
    class LastRead {

        @Test
        void getLastRead_returns_200_with_empty_when_no_userId_header() throws Exception {
            mockMvc.perform(get("/api/chat/rooms/r1/last-read"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.lastReadMessageId").value(""));
        }

        @Test
        void putLastRead_returns_401_when_no_userId_header() throws Exception {
            String body = objectMapper.writeValueAsString(
                    Map.of("lastReadMessageId", "msg-99"));

            mockMvc.perform(put("/api/chat/rooms/r1/last-read")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isUnauthorized());
            verify(readReceiptService, never()).markRead(
                    anyString(), anyString(), anyString(), anyString());
        }

        @Test
        void putLastRead_returns_403_when_not_room_member() throws Exception {
            doThrow(new ForbiddenException("방 멤버가 아닙니다."))
                    .when(membershipGuard).requireMember("r1", "outsider");

            String body = objectMapper.writeValueAsString(
                    Map.of("lastReadMessageId", "msg-99"));

            mockMvc.perform(put("/api/chat/rooms/r1/last-read")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("X-User-Id", "outsider")
                            .header("X-Username", "eve"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false));
            verify(readReceiptService, never()).markRead(
                    anyString(), anyString(), anyString(), anyString());
        }

        @Test
        void putLastRead_calls_markRead_when_lastReadMessageId_provided() throws Exception {
            doNothing().when(membershipGuard).requireMember("r1", "user-1");

            String body = objectMapper.writeValueAsString(
                    Map.of("lastReadMessageId", "msg-99"));

            mockMvc.perform(put("/api/chat/rooms/r1/last-read")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("X-User-Id", "user-1")
                            .header("X-Username", "alice"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            verify(readReceiptService).markRead("r1", "user-1", "alice", "msg-99");
        }

        @Test
        void putLastRead_calls_updateReadAt_only_when_lastReadMessageId_blank() throws Exception {
            doNothing().when(membershipGuard).requireMember("r1", "user-1");

            String body = objectMapper.writeValueAsString(
                    Map.of("lastReadMessageId", ""));

            mockMvc.perform(put("/api/chat/rooms/r1/last-read")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("X-User-Id", "user-1")
                            .header("X-Username", "alice"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            verify(readReceiptService).updateReadAt("r1", "user-1");
            verify(readReceiptService, never()).markRead(
                    eq("r1"), eq("user-1"), eq("alice"), eq(""));
        }
    }
}
