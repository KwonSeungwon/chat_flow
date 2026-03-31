package com.chatflow.chat.controller;

import com.chatflow.chat.entity.ChatMessageEntity;
import com.chatflow.chat.entity.ChatRoom;
import com.chatflow.chat.service.ChatRoomService;
import com.chatflow.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/chat/rooms")
@RequiredArgsConstructor
public class ChatRoomController {

    private final ChatRoomService chatRoomService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<ChatRoom>>> getAllRooms() {
        return ResponseEntity.ok(ApiResponse.ok(chatRoomService.getAllRooms()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ChatRoom>> getRoom(@PathVariable String id) {
        return chatRoomService.getRoom(id)
                .map(room -> ResponseEntity.ok(ApiResponse.ok(room)))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error("채팅방을 찾을 수 없습니다: " + id)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ChatRoom>> createRoom(@Valid @RequestBody ChatRoom request) {
        ChatRoom saved = chatRoomService.createRoom(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(saved, "채팅방이 생성되었습니다."));
    }

    @PostMapping("/get-or-create")
    public ResponseEntity<ApiResponse<ChatRoom>> getOrCreateRoom(@RequestBody GetOrCreateRequest request) {
        if (request.externalId == null || request.externalId.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("externalId는 필수입니다."));
        }
        ChatRoom room = chatRoomService.getOrCreateByExternalId(request.externalId, request.name, request.description);
        return ResponseEntity.ok(ApiResponse.ok(room));
    }

    @GetMapping("/{roomId}/messages")
    public ResponseEntity<ApiResponse<Page<ChatMessageEntity>>> getMessages(
            @PathVariable String roomId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        size = Math.min(size, 100);
        Page<ChatMessageEntity> messages = chatRoomService.getMessages(roomId, PageRequest.of(page, size));
        return ResponseEntity.ok(ApiResponse.ok(messages));
    }

    /**
     * 커서 기반 페이징 — 무한 스크롤에 최적화.
     * before 파라미터 없으면 최신 메시지부터 반환.
     */
    @GetMapping("/{roomId}/messages/cursor")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMessagesByCursor(
            @PathVariable String roomId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime before,
            @RequestParam(defaultValue = "50") int size) {
        size = Math.min(size, 100);
        List<ChatMessageEntity> messages = chatRoomService.getMessagesByCursor(roomId, before, size);

        LocalDateTime nextCursor = messages.isEmpty() ? null
                : messages.get(messages.size() - 1).getTimestamp();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("messages", messages);
        result.put("nextCursor", nextCursor);
        result.put("hasMore", messages.size() == size);

        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    public record GetOrCreateRequest(String externalId, String name, String description) {}
}
