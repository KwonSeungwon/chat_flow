package com.chatflow.chat.controller;

import com.chatflow.chat.auth.AuthInterceptor;
import com.chatflow.chat.auth.AuthenticatedUserResolver;
import com.chatflow.chat.dto.MentionItemDto;
import com.chatflow.chat.exception.GlobalExceptionHandler;
import com.chatflow.chat.service.notification.MentionDigestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class MentionDigestControllerTest {

    private MockMvc mockMvc;

    @Mock private MentionDigestService service;

    @Mock private RoomMembershipGuard membershipGuard;

    @InjectMocks
    private MentionDigestController controller;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticatedUserResolver())
                .addInterceptors(new AuthInterceptor(membershipGuard))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // -- GET /api/chat/mentions -----------------------------------------------

    @Nested
    @DisplayName("GET /api/chat/mentions")
    class ListMentions {

        @Test
        @DisplayName("401 when X-User-Id header missing")
        void returns_401_when_X_User_Id_missing() throws Exception {
            mockMvc.perform(get("/api/chat/mentions")
                            .header("X-Username", "alice"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("200 with list when authenticated")
        void returns_200_with_list_when_authenticated() throws Exception {
            List<MentionItemDto> items = List.of(
                    new MentionItemDto("msg-1", "room-1", "bob",
                            "Hey @alice check this", LocalDateTime.of(2026, 5, 20, 10, 0), false),
                    new MentionItemDto("msg-2", "room-2", "charlie",
                            "@alice urgent", LocalDateTime.of(2026, 5, 20, 11, 0), true)
            );
            when(service.list(eq("user-1"), eq("alice"), anyInt())).thenReturn(items);

            mockMvc.perform(get("/api/chat/mentions")
                            .header("X-User-Id", "user-1")
                            .header("X-Username", "alice"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.length()").value(2))
                    .andExpect(jsonPath("$.data[0].messageId").value("msg-1"))
                    .andExpect(jsonPath("$.data[0].chatRoomId").value("room-1"))
                    .andExpect(jsonPath("$.data[0].fromUsername").value("bob"))
                    .andExpect(jsonPath("$.data[0].read").value(false))
                    .andExpect(jsonPath("$.data[1].messageId").value("msg-2"))
                    .andExpect(jsonPath("$.data[1].read").value(true));
        }
    }

    // -- POST /api/chat/mentions/{messageId}/read (acknowledge) ---------------

    @Nested
    @DisplayName("POST /api/chat/mentions/{messageId}/read")
    class MarkRead {

        @Test
        @DisplayName("markRead calls service with userId and messageId, returns 200")
        void acknowledge_endpoint_calls_service_with_userId_and_returns_200() throws Exception {
            mockMvc.perform(post("/api/chat/mentions/msg-42/read")
                            .header("X-User-Id", "user-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            verify(service).markRead("user-1", "msg-42");
        }
    }
}
