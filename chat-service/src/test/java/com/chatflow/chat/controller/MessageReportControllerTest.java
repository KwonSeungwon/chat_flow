package com.chatflow.chat.controller;

import com.chatflow.chat.dto.ReportDto;
import com.chatflow.chat.entity.ReportReason;
import com.chatflow.chat.entity.ReportStatus;
import com.chatflow.chat.exception.*;
import com.chatflow.chat.service.MessageReportService;
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
class MessageReportControllerTest {

    private MockMvc mockMvc;

    @Mock
    private MessageReportService messageReportService;

    @InjectMocks
    private MessageReportController controller;

    private static final String ROOM_ID = "room-1";
    private static final String CALLER_ID = "caller-1";
    private static final String MESSAGE_ID = "msg-1";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ── POST /messages/{messageId}/reports ───────────────────────

    @Nested
    class SubmitReportTests {

        @Test
        void submitReport_returns201WithReportId() throws Exception {
            when(messageReportService.submitReport(eq(MESSAGE_ID), eq(CALLER_ID),
                    eq(ReportReason.SPAM), eq("looks like spam")))
                    .thenReturn(42L);

            mockMvc.perform(post("/api/chat/messages/{messageId}/reports", MESSAGE_ID)
                            .header("X-User-Id", CALLER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"SPAM\",\"comment\":\"looks like spam\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.reportId").value(42));
        }

        @Test
        void submitReport_caseInsensitiveReason_returnsOk() throws Exception {
            when(messageReportService.submitReport(eq(MESSAGE_ID), eq(CALLER_ID),
                    eq(ReportReason.HARASSMENT), isNull()))
                    .thenReturn(43L);

            mockMvc.perform(post("/api/chat/messages/{messageId}/reports", MESSAGE_ID)
                            .header("X-User-Id", CALLER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"harassment\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.reportId").value(43));
        }

        @Test
        void submitReport_selfReport_returns400() throws Exception {
            doThrow(new SelfReportNotAllowedException("Cannot report own message"))
                    .when(messageReportService).submitReport(anyString(), anyString(), any(), any());

            mockMvc.perform(post("/api/chat/messages/{messageId}/reports", MESSAGE_ID)
                            .header("X-User-Id", CALLER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"SPAM\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("SELF_REPORT_NOT_ALLOWED"));
        }

        @Test
        void submitReport_rateLimit_returns429() throws Exception {
            doThrow(new ReportRateLimitException("Rate limit exceeded"))
                    .when(messageReportService).submitReport(anyString(), anyString(), any(), any());

            mockMvc.perform(post("/api/chat/messages/{messageId}/reports", MESSAGE_ID)
                            .header("X-User-Id", CALLER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"SPAM\"}"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.code").value("REPORT_RATE_LIMIT"));
        }

        @Test
        void submitReport_messageNotFound_returns404() throws Exception {
            doThrow(new MessageNotFoundException("Message not found"))
                    .when(messageReportService).submitReport(anyString(), anyString(), any(), any());

            mockMvc.perform(post("/api/chat/messages/{messageId}/reports", MESSAGE_ID)
                            .header("X-User-Id", CALLER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"SPAM\"}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("MESSAGE_NOT_FOUND"));
        }

        @Test
        void submitReport_missingReason_returns400() throws Exception {
            mockMvc.perform(post("/api/chat/messages/{messageId}/reports", MESSAGE_ID)
                            .header("X-User-Id", CALLER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"comment\":\"no reason given\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void submitReport_invalidReason_returns400() throws Exception {
            mockMvc.perform(post("/api/chat/messages/{messageId}/reports", MESSAGE_ID)
                            .header("X-User-Id", CALLER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"INVALID_REASON\"}"))
                    .andExpect(status().isBadRequest());
        }
    }

    // ── GET /rooms/{roomId}/reports ─────────────────────────────

    @Nested
    class ListReportsTests {

        @Test
        void listReports_returnsOk() throws Exception {
            ReportDto dto = new ReportDto(
                    1L, MESSAGE_ID, "bad content", "author1",
                    "reporter1", "reporter-user-id", ReportReason.SPAM,
                    "looks like spam", ReportStatus.PENDING,
                    LocalDateTime.of(2026, 4, 27, 12, 0));
            when(messageReportService.listPendingReports(ROOM_ID, CALLER_ID))
                    .thenReturn(List.of(dto));

            mockMvc.perform(get("/api/chat/rooms/{roomId}/reports", ROOM_ID)
                            .header("X-User-Id", CALLER_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value(1))
                    .andExpect(jsonPath("$.data[0].messageId").value(MESSAGE_ID))
                    .andExpect(jsonPath("$.data[0].reason").value("SPAM"));
        }

        @Test
        void listReports_permissionDenied_returns403() throws Exception {
            doThrow(new PermissionDeniedException("Members cannot view reports"))
                    .when(messageReportService).listPendingReports(anyString(), anyString());

            mockMvc.perform(get("/api/chat/rooms/{roomId}/reports", ROOM_ID)
                            .header("X-User-Id", CALLER_ID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }
    }

    // ── PATCH /reports/{reportId} ───────────────────────────────

    @Nested
    class UpdateReportStatusTests {

        @Test
        void updateStatus_resolved_returnsOk() throws Exception {
            mockMvc.perform(patch("/api/chat/reports/{reportId}", 1L)
                            .header("X-User-Id", CALLER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"RESOLVED\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            verify(messageReportService).updateStatus(1L, CALLER_ID, ReportStatus.RESOLVED);
        }

        @Test
        void updateStatus_dismissed_returnsOk() throws Exception {
            mockMvc.perform(patch("/api/chat/reports/{reportId}", 2L)
                            .header("X-User-Id", CALLER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"DISMISSED\"}"))
                    .andExpect(status().isOk());

            verify(messageReportService).updateStatus(2L, CALLER_ID, ReportStatus.DISMISSED);
        }

        @Test
        void updateStatus_invalidStatus_returns400() throws Exception {
            mockMvc.perform(patch("/api/chat/reports/{reportId}", 1L)
                            .header("X-User-Id", CALLER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"INVALID\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void updateStatus_blankStatus_returns400() throws Exception {
            mockMvc.perform(patch("/api/chat/reports/{reportId}", 1L)
                            .header("X-User-Id", CALLER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void updateStatus_pendingNotAllowed_returns400() throws Exception {
            doThrow(new IllegalArgumentException("Cannot set to PENDING"))
                    .when(messageReportService).updateStatus(anyLong(), anyString(), eq(ReportStatus.PENDING));

            mockMvc.perform(patch("/api/chat/reports/{reportId}", 1L)
                            .header("X-User-Id", CALLER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"PENDING\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void updateStatus_permissionDenied_returns403() throws Exception {
            doThrow(new PermissionDeniedException("Not authorized"))
                    .when(messageReportService).updateStatus(anyLong(), anyString(), any());

            mockMvc.perform(patch("/api/chat/reports/{reportId}", 1L)
                            .header("X-User-Id", CALLER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"RESOLVED\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }
    }
}
