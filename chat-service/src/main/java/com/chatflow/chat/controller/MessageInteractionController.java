package com.chatflow.chat.controller;

import com.chatflow.chat.auth.AuthenticatedUser;
import com.chatflow.chat.auth.RequireAuth;
import com.chatflow.chat.auth.RequireMember;
import com.chatflow.chat.mapper.MessageEditHistoryMapper;
import com.chatflow.chat.repository.ChatMessageRepository;
import com.chatflow.chat.repository.MessageEditHistoryRepository;
import com.chatflow.chat.service.LinkPreviewService;
import com.chatflow.common.dto.MessageEditHistory;
import com.chatflow.chat.result.ChatErrorCode;
import com.chatflow.chat.result.ErrorResponses;
import com.chatflow.chat.result.Result;
import com.chatflow.chat.service.message.MessageEditService;
import com.chatflow.chat.service.message.MessagePinService;
import com.chatflow.chat.service.message.MessageReactionService;
import com.chatflow.chat.service.message.MessageThreadService;
import com.chatflow.common.dto.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
    private final ChatMessageRepository chatMessageRepository;
    private final MessageEditHistoryRepository editHistoryRepository;
    private final MessageEditHistoryMapper messageEditHistoryMapper;

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
            @Valid @RequestBody EditMessageRequest request,
            @AuthenticatedUser String userId) {
        Result<Void, ChatErrorCode> result = messageEditService.editMessage(messageId, userId, request.content().trim());
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
            @Valid @RequestBody ReactionRequest request,
            @AuthenticatedUser String userId) {
        Result<Boolean, ChatErrorCode> result = messageReactionService.toggleReaction(roomId, messageId, request.emoji(), userId);
        if (result.isFailure()) return ErrorResponses.from(result);
        return ResponseEntity.ok(ApiResponse.ok(result.value()));
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
        // Verify the message exists AND belongs to the authorized room.
        // Without this, a member of room A could read edit history of room B's
        // messages by supplying B's messageId in the URL.
        boolean messageInRoom = chatMessageRepository.findById(messageId)
                .filter(m -> roomId.equals(m.getChatRoomId()))
                .isPresent();
        if (!messageInRoom) {
            return ErrorResponses.from(
                    Result.err(ChatErrorCode.NOT_FOUND, "메시지를 찾을 수 없습니다."));
        }
        List<MessageEditHistory> history = messageEditHistoryMapper.toDtoList(
                editHistoryRepository.findByMessageIdOrderByEditedAtDesc(messageId));
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
            @Valid @RequestBody PinRequest request,
            @AuthenticatedUser String userId) {
        Result<Void, ChatErrorCode> result = messagePinService.pinMessage(roomId, request.messageId());
        if (result.isFailure()) return ErrorResponses.from(result);
        return ResponseEntity.ok(ApiResponse.ok(true));
    }

    @RequireMember
    @DeleteMapping("/{roomId}/pin")
    public ResponseEntity<?> unpinMessage(
            @PathVariable String roomId,
            @AuthenticatedUser String userId) {
        Result<Void, ChatErrorCode> result = messagePinService.unpinMessage(roomId);
        if (result.isFailure()) return ErrorResponses.from(result);
        return ResponseEntity.ok(ApiResponse.ok(true));
    }

    @GetMapping("/link-preview")
    public ResponseEntity<?> linkPreview(@RequestParam String url) {
        Result<Map<String, String>, ChatErrorCode> result = linkPreviewService.fetch(url);
        if (result.isFailure()) return ErrorResponses.from(result);
        return ResponseEntity.ok(ApiResponse.ok(result.value()));
    }

    // ── Request records ─────────────────────────────────────────

    public record EditMessageRequest(
            @NotBlank(message = "수정할 내용이 필요합니다")
            @Size(max = 10_000, message = "메시지는 10,000자를 초과할 수 없습니다")
            String content
    ) {}

    public record ReactionRequest(
            @NotBlank(message = "emoji가 필요합니다")
            String emoji
    ) {}

    public record PinRequest(
            @NotBlank(message = "messageId가 필요합니다")
            String messageId
    ) {}
}
