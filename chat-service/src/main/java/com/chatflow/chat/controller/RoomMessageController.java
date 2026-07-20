package com.chatflow.chat.controller;

import com.chatflow.chat.auth.AuthenticatedUser;
import com.chatflow.chat.auth.RequireMember;
import com.chatflow.chat.entity.ChatMessageEntity;
import com.chatflow.chat.mapper.ChatMessageResponseMapper;
import com.chatflow.chat.service.message.MessageSenderService;
import com.chatflow.chat.service.moderation.AuditService;
import com.chatflow.chat.service.read.MessageReadService;
import com.chatflow.common.dto.ApiResponse;
import com.chatflow.common.dto.AuditEvent;
import com.chatflow.common.dto.ChatMessage;
import com.chatflow.common.dto.ChatMessageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Message history reads + REST message-send fallback for a room.
 * Split out of ChatRoomController (which retains room lifecycle/membership).
 */
@Slf4j
@RestController
@RequestMapping("/api/chat/rooms")
@RequiredArgsConstructor
public class RoomMessageController {

    private final MessageReadService messageReadService;
    private final ChatMessageResponseMapper chatMessageResponseMapper;
    private final MessageSenderService messageSenderService;
    private final AuditService auditService;

    @RequireMember
    @GetMapping("/{roomId}/messages")
    public ResponseEntity<?> getMessages(
            @PathVariable String roomId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @AuthenticatedUser String userId,
            @RequestHeader(value = "X-Username", required = false) String username) {
        size = Math.min(size, 100);
        Page<ChatMessageResponse> messages = messageReadService.getMessages(roomId, PageRequest.of(page, size))
                .map(chatMessageResponseMapper::toResponse);
        auditService.logAccess(userId, username, roomId, AuditEvent.MESSAGE_READ);
        return ResponseEntity.ok(ApiResponse.ok(messages));
    }

    /**
     * 커서 기반 페이징 — 무한 스크롤에 최적화.
     * before 파라미터 없으면 최신 메시지부터 반환.
     */
    @RequireMember
    @GetMapping("/{roomId}/messages/cursor")
    public ResponseEntity<?> getMessagesByCursor(
            @PathVariable String roomId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime before,
            @RequestParam(defaultValue = "50") int size,
            @AuthenticatedUser String userId) {
        size = Math.min(size, 100);
        List<ChatMessageEntity> entities = messageReadService.getMessagesByCursor(roomId, before, size);

        LocalDateTime nextCursor = entities.isEmpty() ? null
                : entities.get(entities.size() - 1).getTimestamp();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("messages", chatMessageResponseMapper.toResponseList(entities));
        result.put("nextCursor", nextCursor);
        result.put("hasMore", entities.size() == size);

        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    /**
     * REST fallback for sending a message when STOMP is disconnected.
     * Also used for forwarded messages with forwardedFrom metadata.
     */
    @RequireMember
    @PostMapping("/{roomId}/messages")
    public ResponseEntity<?> sendMessage(
            @PathVariable String roomId,
            @Valid @RequestBody SendMessageRequest body,
            @AuthenticatedUser String userId,
            @RequestHeader(value = "X-Username", required = false) String username) {
        ChatMessage msg = new ChatMessage();
        msg.setChatRoomId(roomId);
        msg.setUserId(userId);
        msg.setUsername(username != null ? username : userId);
        msg.setContent(body.content());
        msg.setType(ChatMessage.MessageType.CHAT);
        msg.setPriority(body.priority() != null && !body.priority().isBlank()
                ? body.priority() : "ROUTINE");
        if (body.parentMessageId() != null && !body.parentMessageId().isBlank()) {
            msg.setParentMessageId(body.parentMessageId());
        }
        if (body.forwardedFrom() != null && !body.forwardedFrom().isBlank()) {
            msg.setForwardedFrom(body.forwardedFrom());
        }
        if (body.fileUrl() != null && !body.fileUrl().isBlank()) {
            msg.setFileUrl(body.fileUrl());
            msg.setFileName(body.fileName());
            msg.setFileContentType(body.fileContentType());
        }
        messageSenderService.send(msg);
        return ResponseEntity.ok(ApiResponse.ok(null, "메시지를 전송했습니다."));
    }

    public record SendMessageRequest(
            @NotBlank(message = "content는 필수입니다")
            String content,

            String priority,
            String parentMessageId,
            String forwardedFrom,
            String fileUrl,
            String fileName,
            String fileContentType
    ) {}
}
