package com.chatflow.aisummary.controller;

import com.chatflow.aisummary.client.RoomMembershipClient;
import com.chatflow.aisummary.dto.QuickReplyResponse;
import com.chatflow.aisummary.exception.RoomAccessDeniedException;
import com.chatflow.aisummary.exception.UnauthenticatedException;
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

/**
 * AI summary / Q&A endpoints. Every route is room-scoped: the caller
 * (gateway-injected {@code X-User-Id}) must be a member of the room.
 */
@Slf4j
@RestController
@RequestMapping("/api/ai-summary")
@RequiredArgsConstructor
public class AiSummaryController {

    private final AiSummaryService aiSummaryService;
    private final QuickReplyService quickReplyService;
    private final RoomMembershipClient roomMembershipClient;

    private static final String USER_ID_HEADER = "X-User-Id";

    @GetMapping("/room/{roomId}")
    public ResponseEntity<ApiResponse<List<ChatMessage>>> getRoomSummaries(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @PathVariable String roomId) {
        requireUser(userId);
        requireMember(roomId, userId);
        List<ChatMessage> summaries = aiSummaryService.getSummaries(roomId);
        return ResponseEntity.ok(ApiResponse.ok(summaries));
    }

    @PostMapping("/ask")
    public ResponseEntity<ApiResponse<ChatMessage>> askQuestion(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @RequestBody Map<String, String> request) {
        requireUser(userId);
        String roomId = request.get("chatRoomId");
        String question = request.get("question");
        if (roomId == null || roomId.isBlank() || question == null || question.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("chatRoomId와 question은 필수입니다."));
        }
        requireMember(roomId, userId);
        ChatMessage response = aiSummaryService.answerQuestion(roomId, question);
        return ResponseEntity.ok(ApiResponse.ok(response, "AI 답변이 생성되었습니다."));
    }

    @PostMapping("/shift-report")
    public ResponseEntity<ApiResponse<ChatMessage>> requestShiftReport(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @RequestBody Map<String, String> request) {
        requireUser(userId);
        String roomId = request.get("chatRoomId");
        if (roomId == null || roomId.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("chatRoomId는 필수입니다."));
        }
        requireMember(roomId, userId);
        ChatMessage report = aiSummaryService.generateShiftReport(roomId);
        return ResponseEntity.ok(ApiResponse.ok(report, "교대 보고서가 생성되었습니다."));
    }

    @PostMapping("/quick-replies")
    public ResponseEntity<ApiResponse<QuickReplyResponse>> quickReplies(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @RequestBody Map<String, String> body) {
        requireUser(userId);
        String roomId = body.get("chatRoomId");
        String latestMessageId = body.get("latestMessageId");
        if (roomId == null || roomId.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("chatRoomId is required"));
        }
        if (latestMessageId == null || latestMessageId.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("latestMessageId is required"));
        }
        requireMember(roomId, userId);
        QuickReplyResponse result = quickReplyService.generateQuickReplies(roomId, latestMessageId);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @PostMapping("/request")
    public ResponseEntity<ApiResponse<Void>> requestSummary(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @RequestBody Map<String, String> request) {
        requireUser(userId);
        String roomId = request.get("chatRoomId");
        if (roomId == null || roomId.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("chatRoomId는 필수입니다."));
        }
        requireMember(roomId, userId);

        return switch (aiSummaryService.requestSummaryOutcome(roomId)) {
            case GENERATED -> ResponseEntity.ok(ApiResponse.ok(null, "요약 요청이 접수되었습니다."));
            case NO_MESSAGES -> ResponseEntity.ok(ApiResponse.error("요약할 대화 내용이 부족합니다."));
            case RATE_LIMITED -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error("AI 호출 한도를 초과했습니다. 잠시 후 자동으로 다시 시도됩니다."));
            case FAILED -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResponse.error("요약 생성에 실패했습니다. 잠시 후 다시 시도해 주세요."));
        };
    }

    private static void requireUser(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new UnauthenticatedException("X-User-Id 헤더 누락");
        }
    }

    private void requireMember(String roomId, String userId) {
        if (!roomMembershipClient.isMember(roomId, userId)) {
            throw new RoomAccessDeniedException("user " + userId + " is not a member of room " + roomId);
        }
    }
}
