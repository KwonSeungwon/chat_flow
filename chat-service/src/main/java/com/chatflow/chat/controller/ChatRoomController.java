package com.chatflow.chat.controller;

import com.chatflow.chat.entity.ChatMessageEntity;
import com.chatflow.chat.entity.ChatRoom;
import com.chatflow.chat.service.AuditService;
import com.chatflow.chat.service.ChatRoomService;
import com.chatflow.chat.service.ReadReceiptService;
import com.chatflow.common.dto.ApiResponse;
import com.chatflow.common.dto.AuditEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.RequestHeader;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/chat/rooms")
@RequiredArgsConstructor
public class ChatRoomController {

    private final ChatRoomService chatRoomService;
    private final AuditService auditService;
    private final StringRedisTemplate redisTemplate;
    private final ReadReceiptService readReceiptService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<ChatRoom>>> getAllRooms() {
        return ResponseEntity.ok(ApiResponse.ok(chatRoomService.getAllRooms()));
    }

    @GetMapping("/unread-counts")
    public ResponseEntity<ApiResponse<Map<String, Long>>> getUnreadCounts(
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.ok(ApiResponse.ok(Map.of()));
        }
        List<ChatRoom> rooms = chatRoomService.getAllRooms();
        List<String> roomIds = rooms.stream().map(ChatRoom::getId).collect(Collectors.toList());
        Map<String, Long> counts = chatRoomService.getUnreadCounts(userId, roomIds);
        return ResponseEntity.ok(ApiResponse.ok(counts));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ChatRoom>> getRoom(@PathVariable String id) {
        return chatRoomService.getRoom(id)
                .map(room -> ResponseEntity.ok(ApiResponse.ok(room)))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error("채팅방을 찾을 수 없습니다.")));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ChatRoom>> createRoom(
            @Valid @RequestBody ChatRoom request,
            @RequestHeader(value = "X-User-Id", required = false) String creatorId) {
        ChatRoom saved = chatRoomService.createRoom(request, creatorId);
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
            @RequestParam(defaultValue = "50") int size,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Username", required = false) String username) {
        size = Math.min(size, 100);
        Page<ChatMessageEntity> messages = chatRoomService.getMessages(roomId, PageRequest.of(page, size));
        auditService.logAccess(userId, username, roomId, AuditEvent.MESSAGE_READ);
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

    @PostMapping("/{roomId}/verify")
    public ResponseEntity<ApiResponse<Boolean>> verifyPassword(
            @PathVariable String roomId,
            @RequestBody Map<String, String> request) {
        String password = request.get("password");
        boolean valid = chatRoomService.verifyRoomPassword(roomId, password);
        if (valid) {
            return ResponseEntity.ok(ApiResponse.ok(true, "인증 성공"));
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("비밀번호가 일치하지 않습니다."));
    }

    @GetMapping("/{roomId}/participants")
    public ResponseEntity<ApiResponse<List<Map<String, String>>>> getParticipants(
            @PathVariable String roomId) {
        String key = "chatflow:room:participants:" + roomId;
        Set<String> members = redisTemplate.opsForSet().members(key);
        // Deduplicate by userId — same user may have multiple sessions
        Map<String, String> seen = new LinkedHashMap<>();
        if (members != null) {
            for (String entry : members) {
                // Format: userId:sessionId:username (3 parts, split on first and last colon)
                int firstIdx = entry.indexOf(':');
                int lastIdx = entry.lastIndexOf(':');
                if (firstIdx > 0 && lastIdx > firstIdx) {
                    String userId = entry.substring(0, firstIdx);
                    String username = entry.substring(lastIdx + 1);
                    seen.putIfAbsent(userId, username);
                }
            }
        }
        List<Map<String, String>> result = new ArrayList<>();
        seen.forEach((uid, uname) -> {
            Map<String, String> p = new LinkedHashMap<>();
            p.put("userId", uid);
            p.put("username", uname);
            result.add(p);
        });
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @PostMapping("/{roomId}/invite")
    public ResponseEntity<ApiResponse<Void>> inviteUser(
            @PathVariable String roomId,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-User-Id", required = false) String inviterId,
            @RequestHeader(value = "X-Username", required = false) String inviterName) {
        // 채팅방 존재 여부 확인
        ChatRoom room = chatRoomService.getRoom(roomId).orElse(null);
        if (room == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("채팅방을 찾을 수 없습니다."));
        }
        // 초대 허용 여부 확인
        if (!room.isAllowInvites()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("이 채팅방은 초대가 비활성화되어 있습니다."));
        }
        if (chatRoomService.isRoomFull(roomId)) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("채팅방이 만석입니다 (최대 10명)."));
        }
        String targetUsername = body.get("targetUsername");
        if (targetUsername == null || targetUsername.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("targetUsername이 필요합니다."));
        }
        // 이미 참여 중인 멤버 중복 초대 방지
        String participantKey = "chatflow:room:participants:" + roomId;
        Set<String> members = redisTemplate.opsForSet().members(participantKey);
        if (members != null) {
            final String target = targetUsername.toLowerCase();
            boolean alreadyPresent = members.stream()
                    .anyMatch(e -> e.toLowerCase().endsWith(":" + target));
            if (alreadyPresent) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error(targetUsername + "님은 이미 채팅방에 참여 중입니다."));
            }
        }
        chatRoomService.sendInviteMessage(roomId, inviterName, targetUsername);
        return ResponseEntity.ok(ApiResponse.ok(null, "초대 메시지를 보냈습니다."));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteRoom(
            @PathVariable String id,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("인증이 필요합니다."));
        }
        ChatRoom room = chatRoomService.getRoom(id).orElse(null);
        if (room == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("채팅방을 찾을 수 없습니다."));
        }
        // createdBy가 설정된 방은 방장만 삭제 가능 (null이면 레거시 방 — 제한 없음)
        if (room.getCreatedBy() != null && !room.getCreatedBy().equals(userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("채팅방 삭제 권한이 없습니다. 방장만 삭제할 수 있습니다."));
        }
        chatRoomService.deleteRoom(id);
        return ResponseEntity.ok(ApiResponse.ok(null, "채팅방이 삭제되었습니다."));
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
        boolean deleted = chatRoomService.deleteMessage(messageId, userId);
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
        boolean edited = chatRoomService.editMessage(messageId, userId, newContent.trim());
        if (!edited) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("수정 권한이 없거나 메시지를 찾을 수 없습니다."));
        }
        return ResponseEntity.ok(ApiResponse.ok(null, "메시지가 수정되었습니다."));
    }

    @DeleteMapping("/{roomId}/members/me")
    public ResponseEntity<ApiResponse<Void>> leaveRoom(
            @PathVariable String roomId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Username", required = false) String username) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("인증이 필요합니다."));
        }
        if (username == null || username.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("username이 필요합니다."));
        }
        chatRoomService.leaveRoom(roomId, userId, username);
        return ResponseEntity.ok(ApiResponse.ok(null, username + "님이 채팅방을 나갔습니다."));
    }

    @GetMapping("/{roomId}/last-read")
    public ResponseEntity<ApiResponse<Map<String, String>>> getLastRead(
            @PathVariable String roomId,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.ok(ApiResponse.ok(Map.of("lastReadMessageId", "")));
        }
        String key = "chatflow:read:" + roomId + ":" + userId;
        String lastReadId = redisTemplate.opsForValue().get(key);
        return ResponseEntity.ok(ApiResponse.ok(
                Map.of("lastReadMessageId", lastReadId != null ? lastReadId : "")));
    }

    @PutMapping("/{roomId}/last-read")
    public ResponseEntity<ApiResponse<Void>> updateLastRead(
            @PathVariable String roomId,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Username", required = false) String username) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.ok(ApiResponse.ok(null));
        }
        String lastReadMessageId = body.get("lastReadMessageId");
        if (lastReadMessageId == null || lastReadMessageId.isBlank()) {
            return ResponseEntity.ok(ApiResponse.ok(null));
        }
        readReceiptService.markRead(roomId, userId, username != null ? username : "", lastReadMessageId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    public record GetOrCreateRequest(String externalId, String name, String description) {}
}
