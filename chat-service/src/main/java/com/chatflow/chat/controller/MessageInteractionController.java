package com.chatflow.chat.controller;

import com.chatflow.chat.auth.AuthenticatedUser;
import com.chatflow.chat.auth.RequireAuth;
import com.chatflow.chat.auth.RequireMember;
import com.chatflow.chat.entity.MessageEditHistoryEntity;
import com.chatflow.chat.repository.MessageEditHistoryRepository;
import com.chatflow.chat.service.LinkPreviewService;
import com.chatflow.chat.result.ChatErrorCode;
import com.chatflow.chat.result.ErrorResponses;
import com.chatflow.chat.result.Result;
import com.chatflow.chat.service.MessageEditService;
import com.chatflow.chat.service.MessagePinService;
import com.chatflow.chat.service.MessageReactionService;
import com.chatflow.chat.service.MessageThreadService;
import com.chatflow.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/chat/rooms")
@RequiredArgsConstructor
public class MessageInteractionController {

    private final MessageEditService messageEditService;
    private final MessageReactionService messageReactionService;
    private final MessagePinService messagePinService;
    private final LinkPreviewService linkPreviewService;
    private final MessageThreadService messageThreadService;
    private final MessageEditHistoryRepository editHistoryRepository;

    @RequireAuth
    @DeleteMapping("/{roomId}/messages/{messageId}")
    public ResponseEntity<ApiResponse<?>> deleteMessage(
            @PathVariable String roomId,
            @PathVariable String messageId,
            @AuthenticatedUser String userId) {
        Result<Void, ChatErrorCode> result = messageEditService.deleteMessage(messageId, userId);
        if (result.isFailure()) {
            return ErrorResponses.from(result);
        }
        return ResponseEntity.ok(ApiResponse.ok(null, "메시지가 삭제되었습니다."));
    }

    @RequireAuth
    @PutMapping("/{roomId}/messages/{messageId}")
    public ResponseEntity<ApiResponse<?>> editMessage(
            @PathVariable String roomId,
            @PathVariable String messageId,
            @RequestBody Map<String, String> body,
            @AuthenticatedUser String userId) {
        String newContent = body.get("content");
        if (newContent == null || newContent.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("수정할 내용이 필요합니다."));
        }
        if (newContent.length() > 10_000) {
            return ResponseEntity.badRequest().body(ApiResponse.error("메시지는 10,000자를 초과할 수 없습니다."));
        }
        Result<Void, ChatErrorCode> result = messageEditService.editMessage(messageId, userId, newContent.trim());
        if (result.isFailure()) {
            return ErrorResponses.from(result);
        }
        return ResponseEntity.ok(ApiResponse.ok(null, "메시지가 수정되었습니다."));
    }

    @RequireMember
    @PostMapping("/{roomId}/messages/{messageId}/reactions")
    public ResponseEntity<?> toggleReaction(
            @PathVariable String roomId,
            @PathVariable String messageId,
            @RequestBody Map<String, String> body,
            @AuthenticatedUser String userId) {
        String emoji = body.get("emoji");
        if (emoji == null) return ResponseEntity.badRequest().body(ApiResponse.error("emoji가 필요합니다."));
        boolean ok = messageReactionService.toggleReaction(messageId, emoji, userId);
        return ResponseEntity.ok(ApiResponse.ok(ok));
    }

    /**
     * Returns the edit history of a message — newest-first list of pre-edit
     * content snapshots. Empty list if the message has never been edited.
     */
    @RequireMember
    @GetMapping("/{roomId}/messages/{messageId}/edits")
    public ResponseEntity<?> getEditHistory(
            @PathVariable String roomId,
            @PathVariable String messageId,
            @AuthenticatedUser String userId) {
        List<MessageEditHistoryEntity> history =
                editHistoryRepository.findByMessageIdOrderByEditedAtDesc(messageId);
        return ResponseEntity.ok(ApiResponse.ok(history));
    }

    @RequireMember
    @GetMapping("/{roomId}/messages/{messageId}/replies")
    public ResponseEntity<?> getReplies(
            @PathVariable String roomId,
            @PathVariable String messageId,
            @AuthenticatedUser String userId) {
        return ResponseEntity.ok(
                ApiResponse.ok(messageThreadService.findReplies(roomId, messageId)));
    }

    @RequireMember
    @PutMapping("/{roomId}/pin")
    public ResponseEntity<?> pinMessage(
            @PathVariable String roomId,
            @RequestBody Map<String, String> body,
            @AuthenticatedUser String userId) {
        String messageId = body.get("messageId");
        if (messageId == null) return ResponseEntity.badRequest().body(ApiResponse.error("messageId가 필요합니다."));
        return ResponseEntity.ok(ApiResponse.ok(messagePinService.pinMessage(roomId, messageId)));
    }

    @RequireMember
    @DeleteMapping("/{roomId}/pin")
    public ResponseEntity<?> unpinMessage(
            @PathVariable String roomId,
            @AuthenticatedUser String userId) {
        return ResponseEntity.ok(ApiResponse.ok(messagePinService.unpinMessage(roomId)));
    }

    @GetMapping("/link-preview")
    public ResponseEntity<ApiResponse<Map<String, String>>> linkPreview(@RequestParam String url) {
        if (url == null || url.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("url이 필요합니다."));
        }
        return ResponseEntity.ok(ApiResponse.ok(linkPreviewService.fetch(url)));
    }
}
