package com.chatflow.chat.controller;

import com.chatflow.chat.auth.AuthInterceptor;
import com.chatflow.chat.auth.AuthenticatedUserResolver;
import com.chatflow.chat.exception.GlobalExceptionHandler;
import com.chatflow.chat.mapper.MessageEditHistoryMapper;
import com.chatflow.chat.repository.ChatMessageRepository;
import com.chatflow.chat.repository.MessageEditHistoryRepository;
import com.chatflow.chat.result.ChatErrorCode;
import com.chatflow.chat.result.Result;
import com.chatflow.chat.service.LinkPreviewService;
import com.chatflow.chat.service.message.MessageEditService;
import com.chatflow.chat.service.message.MessagePinService;
import com.chatflow.chat.service.message.MessageReactionService;
import com.chatflow.chat.service.message.MessageThreadService;
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

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class MessageInteractionControllerValidationTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock private MessageEditService messageEditService;
    @Mock private MessageReactionService messageReactionService;
    @Mock private MessagePinService messagePinService;
    @Mock private LinkPreviewService linkPreviewService;
    @Mock private MessageThreadService messageThreadService;
    @Mock private ChatMessageRepository chatMessageRepository;
    @Mock private MessageEditHistoryRepository editHistoryRepository;
    @Mock private MessageEditHistoryMapper messageEditHistoryMapper;
    @Mock private RoomMembershipGuard membershipGuard;

    @InjectMocks
    private MessageInteractionController controller;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticatedUserResolver())
                .addInterceptors(new AuthInterceptor(membershipGuard))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ── EditMessage ─────────────────────────────────────────────

    @Nested
    @DisplayName("PUT /api/chat/rooms/{roomId}/messages/{messageId}")
    class EditMessage {

        @Test
        @DisplayName("blank content -> 400 VALIDATION_ERROR")
        void returns_400_when_content_blank() throws Exception {
            String body = objectMapper.writeValueAsString(
                    Map.of("content", "   "));

            mockMvc.perform(put("/api/chat/rooms/r1/messages/msg-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("X-User-Id", "user-1"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.fieldErrors.content").exists());

            verify(messageEditService, never()).editMessage(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("missing content -> 400 VALIDATION_ERROR")
        void returns_400_when_content_missing() throws Exception {
            mockMvc.perform(put("/api/chat/rooms/r1/messages/msg-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}")
                            .header("X-User-Id", "user-1"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.fieldErrors.content").exists());

            verify(messageEditService, never()).editMessage(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("over-length content (>10000) -> 400 VALIDATION_ERROR")
        void returns_400_when_content_over_10000() throws Exception {
            String longContent = "a".repeat(10_001);
            String body = objectMapper.writeValueAsString(
                    Map.of("content", longContent));

            mockMvc.perform(put("/api/chat/rooms/r1/messages/msg-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("X-User-Id", "user-1"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.fieldErrors.content").exists());

            verify(messageEditService, never()).editMessage(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("valid content -> delegates to service")
        void delegates_to_service_on_valid_content() throws Exception {
            when(messageEditService.editMessage(eq("msg-1"), eq("user-1"), eq("hello")))
                    .thenReturn(Result.ok(null));

            String body = objectMapper.writeValueAsString(
                    Map.of("content", "hello"));

            mockMvc.perform(put("/api/chat/rooms/r1/messages/msg-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("X-User-Id", "user-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            verify(messageEditService).editMessage("msg-1", "user-1", "hello");
        }
    }

    // ── ToggleReaction ──────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/chat/rooms/{roomId}/messages/{messageId}/reactions")
    class ToggleReaction {

        @Test
        @DisplayName("missing emoji -> 400 VALIDATION_ERROR")
        void returns_400_when_emoji_missing() throws Exception {
            doNothing().when(membershipGuard).requireMember("r1", "user-1");

            mockMvc.perform(post("/api/chat/rooms/r1/messages/msg-1/reactions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}")
                            .header("X-User-Id", "user-1"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.fieldErrors.emoji").exists());

            verify(messageReactionService, never())
                    .toggleReaction(anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("valid emoji -> delegates to service")
        void delegates_to_service_on_valid_emoji() throws Exception {
            doNothing().when(membershipGuard).requireMember("r1", "user-1");
            when(messageReactionService.toggleReaction("r1", "msg-1", "👍", "user-1"))
                    .thenReturn(Result.ok(true));

            String body = objectMapper.writeValueAsString(
                    Map.of("emoji", "👍"));

            mockMvc.perform(post("/api/chat/rooms/r1/messages/msg-1/reactions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("X-User-Id", "user-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data").value(true));
        }
    }

    // ── PinMessage ──────────────────────────────────────────────

    @Nested
    @DisplayName("PUT /api/chat/rooms/{roomId}/pin")
    class PinMessage {

        @Test
        @DisplayName("missing messageId -> 400 VALIDATION_ERROR")
        void returns_400_when_messageId_missing() throws Exception {
            doNothing().when(membershipGuard).requireMember("r1", "user-1");

            mockMvc.perform(put("/api/chat/rooms/r1/pin")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}")
                            .header("X-User-Id", "user-1"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.fieldErrors.messageId").exists());

            verify(messagePinService, never()).pinMessage(anyString(), anyString());
        }

        @Test
        @DisplayName("valid messageId -> delegates to service")
        void delegates_to_service_on_valid_messageId() throws Exception {
            doNothing().when(membershipGuard).requireMember("r1", "user-1");
            when(messagePinService.pinMessage("r1", "msg-42"))
                    .thenReturn(Result.ok(null));

            String body = objectMapper.writeValueAsString(
                    Map.of("messageId", "msg-42"));

            mockMvc.perform(put("/api/chat/rooms/r1/pin")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("X-User-Id", "user-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            verify(messagePinService).pinMessage("r1", "msg-42");
        }
    }
}
