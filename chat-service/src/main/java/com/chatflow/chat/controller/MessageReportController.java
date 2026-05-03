package com.chatflow.chat.controller;

import com.chatflow.chat.dto.ReportDto;
import com.chatflow.chat.dto.ReportStatusUpdateRequest;
import com.chatflow.chat.dto.ReportSubmitRequest;
import com.chatflow.chat.entity.ReportReason;
import com.chatflow.chat.entity.ReportStatus;
import com.chatflow.chat.service.MessageReportService;
import com.chatflow.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
public class MessageReportController {

    private final MessageReportService messageReportService;

    /**
     * POST /api/chat/messages/{messageId}/reports
     * Submits a report for a message. Any room member can call (self-report blocked in service).
     */
    @PostMapping("/api/chat/messages/{messageId}/reports")
    public ResponseEntity<ApiResponse<Map<String, Long>>> submitReport(
            @PathVariable String messageId,
            @RequestBody ReportSubmitRequest request,
            @RequestHeader(value = "X-User-Id", required = true) String callerUserId) {
        if (request.reason() == null || request.reason().isBlank()) {
            throw new IllegalArgumentException("reason은 필수입니다.");
        }

        ReportReason reason = ReportReason.valueOf(request.reason().toUpperCase());

        Long reportId = messageReportService.submitReport(
                messageId, callerUserId, reason, request.comment());
        log.info("Report submitted: messageId={}, reportId={}, by={}", messageId, reportId, callerUserId);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(Map.of("reportId", reportId)));
    }

    /**
     * GET /api/chat/rooms/{roomId}/reports?status=PENDING
     * Lists reports for a room. OWNER or MOD only (enforced in service).
     */
    @GetMapping("/api/chat/rooms/{roomId}/reports")
    public ResponseEntity<ApiResponse<List<ReportDto>>> listReports(
            @PathVariable String roomId,
            @RequestParam(defaultValue = "PENDING") String status,
            @RequestHeader(value = "X-User-Id", required = true) String callerUserId) {
        // Currently only PENDING listing is supported via the service method
        List<ReportDto> reports = messageReportService.listPendingReports(roomId, callerUserId);
        return ResponseEntity.ok(ApiResponse.ok(reports));
    }

    /**
     * PATCH /api/chat/reports/{reportId}
     * Updates a report's status. OWNER or MOD only (enforced in service).
     */
    @PatchMapping("/api/chat/reports/{reportId}")
    public ResponseEntity<ApiResponse<Void>> updateReportStatus(
            @PathVariable Long reportId,
            @RequestBody ReportStatusUpdateRequest request,
            @RequestHeader(value = "X-User-Id", required = true) String callerUserId) {
        if (request.status() == null || request.status().isBlank()) {
            throw new IllegalArgumentException("status는 필수입니다.");
        }

        ReportStatus newStatus = ReportStatus.valueOf(request.status().toUpperCase());

        messageReportService.updateStatus(reportId, callerUserId, newStatus);
        log.info("Report status updated: reportId={}, newStatus={}, by={}",
                reportId, newStatus, callerUserId);

        return ResponseEntity.ok(ApiResponse.ok(null, "신고 상태가 업데이트되었습니다."));
    }
}
