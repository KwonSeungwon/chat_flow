package com.chatflow.chat.controller;

import com.chatflow.chat.auth.AuthenticatedUser;
import com.chatflow.chat.auth.RequireAuth;
import com.chatflow.chat.auth.RequireMember;
import com.chatflow.chat.entity.ChatMessageEntity;
import com.chatflow.chat.entity.ChatRoom;
import com.chatflow.chat.entity.RoomRole;
import com.chatflow.chat.entity.RoomType;
import com.chatflow.chat.mapper.ChatMessageResponseMapper;
import com.chatflow.chat.mapper.ChatRoomMapper;
import com.chatflow.chat.service.moderation.AuditService;
import com.chatflow.chat.service.RoomPermissionService;
import com.chatflow.chat.service.room.ChatRoomService;
import com.chatflow.chat.service.room.DmRoomService;
import com.chatflow.chat.service.read.MessageReadService;
import com.chatflow.chat.service.message.MessageSenderService;
import com.chatflow.chat.service.room.RoomMembershipService;
import com.chatflow.chat.service.room.RoomVisibilityService;
import com.chatflow.chat.result.ChatErrorCode;
import com.chatflow.chat.result.ErrorResponses;
import com.chatflow.chat.result.Result;
import com.chatflow.common.dto.ApiResponse;
import com.chatflow.common.dto.AuditEvent;
import com.chatflow.common.dto.ChatMessage;
import com.chatflow.common.dto.ChatMessageResponse;
import com.chatflow.common.dto.ChatRoomResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.RequestHeader;

import java.time.Instant;
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
    private final RoomMembershipService roomMembershipService;
    private final RoomPermissionService roomPermissionService;
    private final MessageReadService messageReadService;
    private final DmRoomService dmRoomService;
    private final AuditService auditService;
    private final StringRedisTemplate redisTemplate;
    private final RoomVisibilityService roomVisibilityService;
    private final MessageSenderService messageSenderService;
    private final ChatMessageResponseMapper chatMessageResponseMapper;
    private final ChatRoomMapper chatRoomMapper;

    @GetMapping
    public ResponseEntity<ApiResponse<List<ChatRoomResponse>>> getAllRooms(
            @AuthenticatedUser(required = false) String userId) {
        List<ChatRoom> rooms = chatRoomService.getAllRooms();
        if (userId == null) {
            return ResponseEntity.ok(ApiResponse.ok(chatRoomMapper.toResponseList(rooms)));
        }
        Map<String, Instant> hiddenMap = roomVisibilityService.getHiddenMap(userId);
        if (hiddenMap.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.ok(chatRoomMapper.toResponseList(rooms)));
        }
        List<ChatRoom> visible = rooms.stream()
                .filter(r -> roomVisibilityService.isVisible(r, hiddenMap))
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.ok(chatRoomMapper.toResponseList(visible)));
    }

    @RequireMember(pathVar = "id")
    @GetMapping("/{id}")
    public ResponseEntity<?> getRoom(
            @PathVariable String id,
            @AuthenticatedUser String userId) {
        return chatRoomService.getRoom(id)
                .map(room -> ResponseEntity.ok(ApiResponse.ok(chatRoomMapper.toResponse(room))))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error("채팅방을 찾을 수 없습니다.")));
    }

    @RequireAuth
    @PostMapping
    public ResponseEntity<ApiResponse<ChatRoomResponse>> createRoom(
            @Valid @RequestBody CreateRoomRequest request,
            @AuthenticatedUser String creatorId,
            @RequestHeader(value = "X-Username", required = false) String creatorUsername) {
        ChatRoom roomSpec = ChatRoom.builder()
                .name(request.name())
                .description(request.description())
                .color(request.color())
                .roomType(request.roomType() != null
                        ? RoomType.valueOf(request.roomType()) : null)
                .isPrivate(request.isPrivate() != null && request.isPrivate())
                .password(request.password())
                .allowedRoles(request.allowedRoles())
                .allowInvites(request.allowInvites() != null
                        ? request.allowInvites() : true)
                .build();
        ChatRoom saved = chatRoomService.createRoom(roomSpec, creatorId, creatorUsername);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(chatRoomMapper.toResponse(saved), "채팅방이 생성되었습니다."));
    }

    @PostMapping("/get-or-create")
    public ResponseEntity<ApiResponse<ChatRoomResponse>> getOrCreateRoom(@RequestBody GetOrCreateRequest request) {
        if (request.externalId == null || request.externalId.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("externalId는 필수입니다."));
        }
        ChatRoom room = chatRoomService.getOrCreateByExternalId(request.externalId, request.name, request.description);
        return ResponseEntity.ok(ApiResponse.ok(chatRoomMapper.toResponse(room)));
    }

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

    @PostMapping("/{roomId}/verify")
    public ResponseEntity<ApiResponse<Boolean>> verifyPassword(
            @PathVariable String roomId,
            @RequestBody VerifyPasswordRequest request,
            @AuthenticatedUser(required = false) String userId,
            @RequestHeader(value = "X-Username", required = false) String username) {
        boolean valid = chatRoomService.verifyRoomPassword(roomId, request.password());
        if (valid) {
            // Seed membership so subsequent member-gated endpoints work.
            if (userId != null && !userId.isBlank()) {
                roomMembershipService.addMemberIfAbsent(roomId, userId, username);
            }
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

    @RequireAuth
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteRoom(
            @PathVariable String id,
            @AuthenticatedUser String userId) {
        if (chatRoomService.getRoom(id).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("채팅방을 찾을 수 없습니다."));
        }
        // 방장만 삭제 가능 — role-based. createRoom이 생성자를 OWNER로 seed하므로 정상 생성 방은
        // OWNER 행이 존재. (이 엔드포인트는 @RequireAuth라 RoomMembershipGuard의 레거시 backfill은
        // 걸리지 않음 — room_members 행이 없는 pre-seed 레거시 방은 데이터 backfill 필요.)
        roomPermissionService.requireRole(id, userId, RoomRole.OWNER);
        chatRoomService.deleteRoom(id);
        return ResponseEntity.ok(ApiResponse.ok(null, "채팅방이 삭제되었습니다."));
    }

    @RequireAuth
    @DeleteMapping("/{roomId}/members/me")
    public ResponseEntity<ApiResponse<?>> leaveRoom(
            @PathVariable String roomId,
            @AuthenticatedUser String userId,
            @RequestHeader(value = "X-Username", required = false) String username) {
        if (username == null || username.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("username이 필요합니다."));
        }
        Result<Void, ChatErrorCode> result = roomMembershipService.leaveRoom(roomId, userId, username);
        if (result.isFailure()) return ErrorResponses.from(result);
        return ResponseEntity.ok(ApiResponse.ok(null, username + "님이 채팅방을 나갔습니다."));
    }

    /**
     * DM 방 per-user soft-hide. 본인 화면에서만 숨김 처리.
     * 상대가 새 메시지 보내면 자동 재출현 (lastMessageAt > hidden_at).
     * 단체방/HANDOFF는 hide 불가 -- 기존 leave 사용.
     */
    @RequireAuth
    @PostMapping("/{roomId}/hide")
    public ResponseEntity<ApiResponse<Void>> hideRoom(
            @PathVariable String roomId,
            @AuthenticatedUser String userId,
            @RequestHeader(value = "X-Username", required = false) String username) {
        ChatRoom room = chatRoomService.getRoom(roomId).orElse(null);
        if (room == null) {
            auditService.logAccess(userId, username, roomId, AuditEvent.ROOM_HIDE_DENIED);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("채팅방을 찾을 수 없습니다."));
        }
        if (room.getRoomType() != RoomType.DIRECT) {
            auditService.logAccess(userId, username, roomId, AuditEvent.ROOM_HIDE_DENIED);
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("DM 방만 숨길 수 있습니다. 단체방은 나가기를 사용하세요."));
        }
        roomVisibilityService.hide(userId, roomId);
        auditService.logAccess(userId, username, roomId, AuditEvent.ROOM_HIDDEN);
        return ResponseEntity.ok(ApiResponse.ok(null, "방을 숨겼습니다"));
    }

    @RequireAuth
    @PostMapping("/dm")
    public ResponseEntity<ApiResponse<ChatRoomResponse>> createDm(
            @Valid @RequestBody CreateDmRequest body,
            @AuthenticatedUser String userId,
            @RequestHeader(value = "X-Username", required = false) String username) {
        ChatRoom dm = dmRoomService.createOrFindDmRoom(userId, username, body.targetUserId(), body.targetUsername());
        // Seed both DM participants — they may both want to call member-gated
        // endpoints without sending a STOMP message first.
        roomMembershipService.addMemberIfAbsent(dm.getId(), userId, username);
        roomMembershipService.addMemberIfAbsent(dm.getId(), body.targetUserId(), body.targetUsername());
        return ResponseEntity.ok(ApiResponse.ok(chatRoomMapper.toResponse(dm)));
    }

    @RequireAuth
    @PutMapping("/{roomId}/settings")
    public ResponseEntity<?> updateRoomSettings(
            @PathVariable String roomId,
            @RequestBody UpdateSettingsRequest body,
            @AuthenticatedUser String userId) {
        if (chatRoomService.getRoom(roomId).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("채팅방을 찾을 수 없습니다."));
        }
        // 방장만 설정 변경 가능 — role-based. createRoom이 생성자를 OWNER로 seed하므로 정상 생성 방은
        // OWNER 행이 존재. (이 엔드포인트는 @RequireAuth라 RoomMembershipGuard의 레거시 backfill은
        // 걸리지 않음 — room_members 행이 없는 pre-seed 레거시 방은 데이터 backfill 필요.)
        roomPermissionService.requireRole(roomId, userId, RoomRole.OWNER);
        return ResponseEntity.ok(ApiResponse.ok(
                chatRoomService.updateRoomSettings(roomId, body.name(), body.description())));
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

    public record GetOrCreateRequest(String externalId, String name, String description) {}

    // ── Request records ─────────────────────────────────────────

    public record CreateRoomRequest(
            @NotBlank(message = "채팅방 이름은 필수입니다")
            @Size(max = 100, message = "채팅방 이름은 100자를 초과할 수 없습니다")
            String name,

            @Size(max = 500, message = "설명은 500자를 초과할 수 없습니다")
            String description,

            String color,

            String roomType,

            @JsonProperty("isPrivate")
            Boolean isPrivate,

            String password,

            String allowedRoles,

            Boolean allowInvites
    ) {}

    public record VerifyPasswordRequest(String password) {}

    public record CreateDmRequest(
            @NotBlank(message = "targetUserId는 필수입니다")
            String targetUserId,

            @NotBlank(message = "targetUsername은 필수입니다")
            String targetUsername
    ) {}

    public record UpdateSettingsRequest(String name, String description) {}

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
