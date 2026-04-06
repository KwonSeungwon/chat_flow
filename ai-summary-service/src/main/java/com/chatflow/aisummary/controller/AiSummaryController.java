package com.chatflow.aisummary.controller;

import com.chatflow.aisummary.service.AiSummaryService;
import com.chatflow.common.dto.ApiResponse;
import com.chatflow.common.dto.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    @GetMapping("/room/{roomId}")
    public ResponseEntity<List<ChatMessage>> getRoomSummaries(@PathVariable String roomId) {
        List<ChatMessage> summaries = aiSummaryService.getSummaries(roomId);
        return ResponseEntity.ok(summaries);
    }

    @PostMapping("/ask")
    public ResponseEntity<ApiResponse<ChatMessage>> askQuestion(@RequestBody Map<String, String> request) {
        String roomId = request.get("chatRoomId");
        String question = request.get("question");
        if (roomId == null || roomId.isBlank() || question == null || question.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("chatRoomId와 question은 필수입니다."));
        }
        try {
            ChatMessage response = aiSummaryService.answerQuestion(roomId, question);
            return ResponseEntity.ok(ApiResponse.ok(response, "AI 답변이 생성되었습니다."));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(429).body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/shift-report")
    public ResponseEntity<ApiResponse<ChatMessage>> requestShiftReport(@RequestBody Map<String, String> request) {
        String roomId = request.get("chatRoomId");
        if (roomId == null || roomId.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("chatRoomId는 필수입니다."));
        }
        try {
            ChatMessage report = aiSummaryService.generateShiftReport(roomId);
            return ResponseEntity.ok(ApiResponse.ok(report, "교대 보고서가 생성되었습니다."));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(429).body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/request")
    public ResponseEntity<ApiResponse<Void>> requestSummary(@RequestBody Map<String, String> request) {
        String roomId = request.get("chatRoomId");
        if (roomId == null || roomId.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("chatRoomId는 필수입니다."));
        }

        boolean generated = aiSummaryService.requestSummary(roomId);
        if (generated) {
            return ResponseEntity.ok(ApiResponse.ok(null, "요약 요청이 접수되었습니다."));
        } else {
            return ResponseEntity.ok(ApiResponse.error("요약할 대화 내용이 부족합니다."));
        }
    }
}
