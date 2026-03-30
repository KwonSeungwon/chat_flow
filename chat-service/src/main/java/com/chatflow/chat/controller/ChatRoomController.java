package com.chatflow.chat.controller;

import com.chatflow.chat.entity.ChatMessageEntity;
import com.chatflow.chat.entity.ChatRoom;
import com.chatflow.chat.repository.ChatMessageRepository;
import com.chatflow.chat.repository.ChatRoomRepository;
import com.chatflow.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/chat/rooms")
@RequiredArgsConstructor
public class ChatRoomController {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatMessageRepository chatMessageRepository;

    @GetMapping
    public ResponseEntity<ApiResponse<List<ChatRoom>>> getAllRooms() {
        List<ChatRoom> rooms = chatRoomRepository.findAllByOrderByCreatedAtDesc();
        return ResponseEntity.ok(ApiResponse.ok(rooms));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ChatRoom>> getRoom(@PathVariable String id) {
        return chatRoomRepository.findById(id)
                .map(room -> ResponseEntity.ok(ApiResponse.ok(room)))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error("채팅방을 찾을 수 없습니다: " + id)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ChatRoom>> createRoom(@Valid @RequestBody ChatRoom request) {
        ChatRoom room = ChatRoom.builder()
                .id("room_" + UUID.randomUUID().toString().substring(0, 8))
                .name(request.getName().trim())
                .description(request.getDescription())
                .color(request.getColor() != null ? request.getColor() : "#6366f1")
                .isPrivate(request.isPrivate())
                .allowInvites(request.isAllowInvites())
                .participantCount(0)
                .createdAt(LocalDateTime.now())
                .build();

        ChatRoom saved = chatRoomRepository.save(room);
        log.info("Chat room created: {} ({})", saved.getName(), saved.getId());

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(saved, "채팅방이 생성되었습니다."));
    }

    @PostMapping("/get-or-create")
    public ResponseEntity<ApiResponse<ChatRoom>> getOrCreateRoom(@RequestBody GetOrCreateRequest request) {
        if (request.externalId == null || request.externalId.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("externalId는 필수입니다."));
        }

        ChatRoom room = chatRoomRepository.findByExternalId(request.externalId)
                .orElseGet(() -> {
                    ChatRoom newRoom = ChatRoom.builder()
                            .id("ext_" + UUID.randomUUID().toString().substring(0, 8))
                            .externalId(request.externalId)
                            .name(request.name != null ? request.name : request.externalId)
                            .description(request.description)
                            .color("#10b981")
                            .participantCount(0)
                            .createdAt(LocalDateTime.now())
                            .build();
                    log.info("Auto-created room for external ID: {}", request.externalId);
                    return chatRoomRepository.save(newRoom);
                });

        return ResponseEntity.ok(ApiResponse.ok(room));
    }

    @GetMapping("/{roomId}/messages")
    public ResponseEntity<ApiResponse<Page<ChatMessageEntity>>> getMessages(
            @PathVariable String roomId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        size = Math.min(size, 100);
        Page<ChatMessageEntity> messages = chatMessageRepository
                .findByChatRoomIdOrderByTimestampDesc(roomId, PageRequest.of(page, size));
        return ResponseEntity.ok(ApiResponse.ok(messages));
    }

    public record GetOrCreateRequest(String externalId, String name, String description) {}
}
