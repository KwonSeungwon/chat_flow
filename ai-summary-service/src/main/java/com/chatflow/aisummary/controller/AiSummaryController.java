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

    @PostMapping("/request")
    public ResponseEntity<ApiResponse<Void>> requestSummary(@RequestBody Map<String, String> request) {
        String roomId = request.get("chatRoomId");
        if (roomId == null || roomId.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("chatRoomId는 필수입니다."));
        }

        aiSummaryService.requestSummary(roomId);
        return ResponseEntity.ok(ApiResponse.ok(null, "요약 요청이 접수되었습니다."));
    }
}
