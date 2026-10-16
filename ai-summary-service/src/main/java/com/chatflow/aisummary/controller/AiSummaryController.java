package com.chatflow.aisummary.controller;

import com.chatflow.aisummary.dto.QuickReplyResponse;
import com.chatflow.aisummary.service.AiSummaryService;
import com.chatflow.aisummary.service.QuickReplyService;
import com.chatflow.common.dto.ApiResponse;
import com.chatflow.common.dto.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/ai-summary")
@RequiredArgsConstructor
public class AiSummaryController {

    private final AiSummaryService aiSummaryService;
    private final QuickReplyService quickReplyService;

    @GetMapping("/room/{roomId}")
    public ResponseEntity<ApiResponse<List<ChatMessage>>> getRoomSummaries(@PathVariable String roomId) {
        List<ChatMessage> summaries = aiSummaryService.getSummaries(roomId);
        return ResponseEntity.ok(ApiResponse.ok(summaries));
    }

    @PostMapping("/ask")
    public ResponseEntity<ApiResponse<ChatMessage>> askQuestion(@RequestBody Map<String, String> request) {
        String roomId = request.get("chatRoomId");
        String question = request.get("question");
        if (roomId == null || roomId.isBlank() || question == null || question.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("chatRoomId와 question은 필수입니다."));
        }
        ChatMessage response = aiSummaryService.answerQuestion(roomId, question);
        return ResponseEntity.ok(ApiResponse.ok(response, "AI 답변이 생성되었습니다."));
    }

    @PostMapping("/shift-report")
    public ResponseEntity<ApiResponse<ChatMessage>> requestShiftReport(@RequestBody Map<String, String> request) {
        String roomId = request.get("chatRoomId");
        if (roomId == null || roomId.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("chatRoomId는 필수입니다."));
        }
        ChatMessage report = aiSummaryService.generateShiftReport(roomId);
        return ResponseEntity.ok(ApiResponse.ok(report, "교대 보고서가 생성되었습니다."));
    }

    @PostMapping("/quick-replies")
    public ResponseEntity<ApiResponse<QuickReplyResponse>> quickReplies(
            @RequestBody Map<String, String> body) {
        String roomId = body.get("chatRoomId");
        String latestMessageId = body.get("latestMessageId");
        if (roomId == null || roomId.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("chatRoomId is required"));
        }
        if (latestMessageId == null || latestMessageId.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("latestMessageId is required"));
        }
        QuickReplyResponse result = quickReplyService.generateQuickReplies(roomId, latestMessageId);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @PostMapping("/request")
    public ResponseEntity<ApiResponse<Void>> requestSummary(@RequestBody Map<String, String> request) {
        String roomId = request.get("chatRoomId");
        if (roomId == null || roomId.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("chatRoomId는 필수입니다."));
        }

        return switch (aiSummaryService.requestSummaryOutcome(roomId)) {
            case GENERATED -> ResponseEntity.ok(ApiResponse.ok(null, "요약 요청이 접수되었습니다."));
            case NO_MESSAGES -> ResponseEntity.ok(ApiResponse.error("요약할 대화 내용이 부족합니다."));
            case RATE_LIMITED -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error("AI 호출 한도를 초과했습니다. 잠시 후 자동으로 다시 시도됩니다."));
            case FAILED -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResponse.error("요약 생성에 실패했습니다. 잠시 후 다시 시도해 주세요."));
        };
    }
}
