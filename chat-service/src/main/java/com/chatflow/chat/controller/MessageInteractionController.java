package com.chatflow.chat.controller;

import com.chatflow.chat.entity.ChatMessageEntity;
import com.chatflow.chat.entity.ChatRoom;
import com.chatflow.chat.repository.ChatRoomRepository;
import com.chatflow.chat.repository.RoomMemberRepository;
import com.chatflow.chat.service.LinkPreviewService;
import com.chatflow.chat.service.MessageEditService;
import com.chatflow.chat.service.MessagePinService;
import com.chatflow.chat.service.MessageReactionService;
import com.chatflow.chat.service.MessageThreadService;
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
@RequestMapping("/api/chat/rooms")
@RequiredArgsConstructor
public class MessageInteractionController {

    private final MessageEditService messageEditService;
    private final MessageReactionService messageReactionService;
    private final MessagePinService messagePinService;
    private final LinkPreviewService linkPreviewService;
    private final MessageThreadService messageThreadService;
    private final RoomMemberRepository roomMemberRepository;
    private final ChatRoomRepository chatRoomRepository;

    /**
     * Same membership semantics as ChatRoomController.requireMember:
     * 401 if no userId, 403 if not a member (with legacy bridge for
     * pre-seed room creators).
     */
    private ResponseEntity<ApiResponse<?>> requireMember(String roomId, String userId) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("인증이 필요합니다."));
        }
        if (roomMemberRepository.existsByRoomIdAndUserId(roomId, userId)) return null;
        ChatRoom room = chatRoomRepository.findById(roomId).orElse(null);
        if (room != null && userId.equals(room.getCreatedBy())) return null;
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("방 멤버가 아닙니다."));
    }

    @DeleteMapping("/{roomId}/messages/{messageId}")
    public ResponseEntity<ApiResponse<Void>> deleteMessage(
            @PathVariable String roomId,
            @PathVariable String messageId,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("인증이 필요합니다."));
        }
        boolean deleted = messageEditService.deleteMessage(messageId, userId);
        if (!deleted) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("삭제 권한이 없거나 메시지를 찾을 수 없습니다."));
        }
        return ResponseEntity.ok(ApiResponse.ok(null, "메시지가 삭제되었습니다."));
    }

    @PutMapping("/{roomId}/messages/{messageId}")
    public ResponseEntity<ApiResponse<Void>> editMessage(
            @PathVariable String roomId,
            @PathVariable String messageId,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("인증이 필요합니다."));
        }
        String newContent = body.get("content");
        if (newContent == null || newContent.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("수정할 내용이 필요합니다."));
        }
        if (newContent.length() > 10_000) {
            return ResponseEntity.badRequest().body(ApiResponse.error("메시지는 10,000자를 초과할 수 없습니다."));
        }
        boolean edited = messageEditService.editMessage(messageId, userId, newContent.trim());
        if (!edited) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("수정 권한이 없거나 메시지를 찾을 수 없습니다."));
        }
        return ResponseEntity.ok(ApiResponse.ok(null, "메시지가 수정되었습니다."));
    }

    @PostMapping("/{roomId}/messages/{messageId}/reactions")
    public ResponseEntity<?> toggleReaction(
            @PathVariable String roomId,
            @PathVariable String messageId,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        ResponseEntity<ApiResponse<?>> gate = requireMember(roomId, userId);
        if (gate != null) return gate;
        String emoji = body.get("emoji");
        if (emoji == null) return ResponseEntity.badRequest().body(ApiResponse.error("emoji가 필요합니다."));
        boolean ok = messageReactionService.toggleReaction(messageId, emoji, userId);
        return ResponseEntity.ok(ApiResponse.ok(ok));
    }

    @GetMapping("/{roomId}/messages/{messageId}/replies")
    public ResponseEntity<?> getReplies(
            @PathVariable String roomId,
            @PathVariable String messageId,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        ResponseEntity<ApiResponse<?>> gate = requireMember(roomId, userId);
        if (gate != null) return gate;
        return ResponseEntity.ok(
                ApiResponse.ok(messageThreadService.findReplies(roomId, messageId)));
    }

    @PutMapping("/{roomId}/pin")
    public ResponseEntity<?> pinMessage(
            @PathVariable String roomId,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        ResponseEntity<ApiResponse<?>> gate = requireMember(roomId, userId);
        if (gate != null) return gate;
        String messageId = body.get("messageId");
        if (messageId == null) return ResponseEntity.badRequest().body(ApiResponse.error("messageId가 필요합니다."));
        return ResponseEntity.ok(ApiResponse.ok(messagePinService.pinMessage(roomId, messageId)));
    }

    @DeleteMapping("/{roomId}/pin")
    public ResponseEntity<?> unpinMessage(
            @PathVariable String roomId,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        ResponseEntity<ApiResponse<?>> gate = requireMember(roomId, userId);
        if (gate != null) return gate;
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
