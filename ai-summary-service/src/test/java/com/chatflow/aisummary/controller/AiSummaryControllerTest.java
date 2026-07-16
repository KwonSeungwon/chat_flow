package com.chatflow.aisummary.controller;

import com.chatflow.aisummary.exception.AiRateLimitException;
import com.chatflow.aisummary.exception.GlobalExceptionHandler;
import com.chatflow.aisummary.service.AiSummaryService;
import com.chatflow.aisummary.service.QuickReplyService;
import com.chatflow.common.dto.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Controller + GlobalExceptionHandler integration tests (standaloneSetup).
 *
 * Verifies that:
 * - AiRateLimitException -> 429 with ApiResponse body (wire-compatible with the
 *   old controller-local catch)
 * - A stray IllegalStateException from the service now falls through to the
 *   BaseExceptionHandler catch-all -> 500 INTERNAL_ERROR (the regression fix)
 */
@ExtendWith(MockitoExtension.class)
class AiSummaryControllerTest {

    @Mock private AiSummaryService aiSummaryService;
    @Mock private QuickReplyService quickReplyService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AiSummaryController controller = new AiSummaryController(aiSummaryService, quickReplyService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private ChatMessage dummyResponse(String roomId) {
        return ChatMessage.builder()
                .messageId(UUID.randomUUID().toString())
                .chatRoomId(roomId)
                .userId("ai-system")
                .username("ChatFlow AI")
                .content("test answer")
                .type(ChatMessage.MessageType.AI_SUMMARY)
                .timestamp(LocalDateTime.now())
                .isAiGenerated(true)
                .build();
    }

    // ── /ask endpoint ──────────────────────────────────────────────

    @Nested
    class AskQuestion {

        @Test
        void success_returns200() throws Exception {
            when(aiSummaryService.answerQuestion("room-1", "hello?"))
                    .thenReturn(dummyResponse("room-1"));

            mockMvc.perform(post("/api/ai-summary/ask")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"chatRoomId\":\"room-1\",\"question\":\"hello?\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("AI 답변이 생성되었습니다."));
        }

        @Test
        void rateLimitExceeded_returns429_withApiResponseBody() throws Exception {
            when(aiSummaryService.answerQuestion(anyString(), anyString()))
                    .thenThrow(new AiRateLimitException("AI 호출 한도를 초과했습니다. 잠시 후 다시 시도해 주세요."));

            mockMvc.perform(post("/api/ai-summary/ask")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"chatRoomId\":\"room-1\",\"question\":\"hello?\"}"))
                    .andExpect(status().isTooManyRequests())
                    // ApiResponse shape — wire-compatible with old controller catch
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value("AI 호출 한도를 초과했습니다. 잠시 후 다시 시도해 주세요."));
        }

        @Test
        void illegalStateException_fallsThrough_to500() throws Exception {
            // Before this fix, IllegalStateException from chatModelClient.generate()
            // was caught by the local try/catch and mapped to 429. Now it falls
            // through to the BaseExceptionHandler catch-all -> 500 INTERNAL_ERROR.
            when(aiSummaryService.answerQuestion(anyString(), anyString()))
                    .thenThrow(new IllegalStateException("unexpected internal error"));

            mockMvc.perform(post("/api/ai-summary/ask")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"chatRoomId\":\"room-1\",\"question\":\"hello?\"}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                    .andExpect(jsonPath("$.status").value(500));
        }

        @Test
        void missingFields_returns400() throws Exception {
            mockMvc.perform(post("/api/ai-summary/ask")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"chatRoomId\":\"\",\"question\":\"\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value("chatRoomId와 question은 필수입니다."));
        }
    }

    // ── /shift-report endpoint ─────────────────────────────────────

    @Nested
    class ShiftReport {

        @Test
        void success_returns200() throws Exception {
            when(aiSummaryService.generateShiftReport("room-1"))
                    .thenReturn(dummyResponse("room-1"));

            mockMvc.perform(post("/api/ai-summary/shift-report")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"chatRoomId\":\"room-1\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("교대 보고서가 생성되었습니다."));
        }

        @Test
        void rateLimitExceeded_returns429_withApiResponseBody() throws Exception {
            when(aiSummaryService.generateShiftReport(anyString()))
                    .thenThrow(new AiRateLimitException("AI 호출 한도를 초과했습니다. 잠시 후 다시 시도해 주세요."));

            mockMvc.perform(post("/api/ai-summary/shift-report")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"chatRoomId\":\"room-1\"}"))
                    .andExpect(status().isTooManyRequests())
                    // ApiResponse shape — wire-compatible with old controller catch
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value("AI 호출 한도를 초과했습니다. 잠시 후 다시 시도해 주세요."));
        }

        @Test
        void illegalStateException_fallsThrough_to500() throws Exception {
            when(aiSummaryService.generateShiftReport(anyString()))
                    .thenThrow(new IllegalStateException("unexpected internal error"));

            mockMvc.perform(post("/api/ai-summary/shift-report")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"chatRoomId\":\"room-1\"}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                    .andExpect(jsonPath("$.status").value(500));
        }

        @Test
        void missingRoomId_returns400() throws Exception {
            mockMvc.perform(post("/api/ai-summary/shift-report")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"chatRoomId\":\"\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value("chatRoomId는 필수입니다."));
        }
    }
}
