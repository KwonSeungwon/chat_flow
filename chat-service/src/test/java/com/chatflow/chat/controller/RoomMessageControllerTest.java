package com.chatflow.chat.controller;

import com.chatflow.chat.auth.AuthInterceptor;
import com.chatflow.chat.auth.AuthenticatedUserResolver;
import com.chatflow.chat.exception.GlobalExceptionHandler;
import com.chatflow.chat.mapper.ChatMessageResponseMapper;
import com.chatflow.chat.service.message.MessageSenderService;
import com.chatflow.chat.service.moderation.AuditService;
import com.chatflow.chat.service.read.MessageReadService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class RoomMessageControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock private MessageReadService messageReadService;
    @Mock private ChatMessageResponseMapper chatMessageResponseMapper;
    @Mock private MessageSenderService messageSenderService;
    @Mock private AuditService auditService;
    @Mock private RoomMembershipGuard membershipGuard;

    @InjectMocks
    private RoomMessageController controller;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticatedUserResolver())
                .addInterceptors(new AuthInterceptor(membershipGuard))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ── SendMessage (REST fallback) ─────────────────────────────

    @Nested
    @DisplayName("POST /api/chat/rooms/{roomId}/messages")
    class SendMessage {

        @Test
        void returns_400_when_content_missing() throws Exception {
            doNothing().when(membershipGuard).requireMember("r1", "user-1");

            String body = objectMapper.writeValueAsString(
                    Map.of("forwardedFrom", "someone: hello"));

            mockMvc.perform(post("/api/chat/rooms/r1/messages")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("X-User-Id", "user-1"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.fieldErrors.content").exists());

            verify(messageSenderService, never()).send(any());
        }

        @Test
        void returns_400_when_content_blank() throws Exception {
            doNothing().when(membershipGuard).requireMember("r1", "user-1");

            String body = objectMapper.writeValueAsString(
                    Map.of("content", "   "));

            mockMvc.perform(post("/api/chat/rooms/r1/messages")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("X-User-Id", "user-1"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }

        @Test
        void returns_200_and_delegates_to_sender_when_valid() throws Exception {
            doNothing().when(membershipGuard).requireMember("r1", "user-1");

            String body = objectMapper.writeValueAsString(
                    Map.of("content", "hello", "forwardedFrom", "alice: world"));

            mockMvc.perform(post("/api/chat/rooms/r1/messages")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("X-User-Id", "user-1")
                            .header("X-Username", "alice"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            verify(messageSenderService).send(argThat(msg ->
                    "hello".equals(msg.getContent())
                    && "r1".equals(msg.getChatRoomId())
                    && "alice: world".equals(msg.getForwardedFrom())
            ));
        }
    }
}
