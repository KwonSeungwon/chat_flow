package com.chatflow.chat.controller;

import com.chatflow.chat.entity.ChatMessageEntity;
import com.chatflow.chat.entity.ChatRoom;
import com.chatflow.chat.entity.RoomType;
import com.chatflow.chat.service.AuditService;
import com.chatflow.chat.service.ChatRoomService;
import com.chatflow.chat.service.DmRoomService;
import com.chatflow.chat.service.InviteLinkService;
import com.chatflow.chat.service.MessageReadService;
import com.chatflow.chat.service.MessageSenderService;
import com.chatflow.chat.service.ParticipantService;
import com.chatflow.chat.service.ReadReceiptService;
import com.chatflow.chat.service.RoomVisibilityService;
import com.chatflow.chat.service.UnreadCountService;
import com.chatflow.common.dto.ApiResponse;
import com.chatflow.common.dto.AuditEvent;
import com.chatflow.common.dto.ChatMessage;
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
    private final MessageReadService messageReadService;
    private final UnreadCountService unreadCountService;
    private final DmRoomService dmRoomService;
    private final ParticipantService participantService;
    private final AuditService auditService;
    private final StringRedisTemplate redisTemplate;
    private final ReadReceiptService readReceiptService;
    private final RoomVisibilityService roomVisibilityService;
    private final MessageSenderService messageSenderService;
    private final InviteLinkService inviteLinkService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<ChatRoom>>> getAllRooms(
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        List<ChatRoom> rooms = chatRoomService.getAllRooms();
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.ok(ApiResponse.ok(rooms));
        }
        Map<String, Instant> hiddenMap = roomVisibilityService.getHiddenMap(userId);
        if (hiddenMap.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.ok(rooms));
        }
        List<ChatRoom> visible = rooms.stream()
                .filter(r -> roomVisibilityService.isVisible(r, hiddenMap))
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.ok(visible));
    }

    @GetMapping("/unread-counts")
    public ResponseEntity<ApiResponse<Map<String, Long>>> getUnreadCounts(
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.ok(ApiResponse.ok(Map.of()));
        }
        List<ChatRoom> rooms = chatRoomService.getAllRooms();
        List<String> roomIds = rooms.stream().map(ChatRoom::getId).collect(Collectors.toList());
        Map<String, Long> counts = unreadCountService.getUnreadCounts(userId, roomIds);
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
            @RequestHeader(value = "X-User-Id", required = false) String creatorId,
            @RequestHeader(value = "X-Username", required = false) String creatorUsername) {
        if (creatorId == null || creatorId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("인증이 필요합니다."));
        }
        ChatRoom saved = chatRoomService.createRoom(request, creatorId, creatorUsername);
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
        Page<ChatMessageEntity> messages = messageReadService.getMessages(roomId, PageRequest.of(page, size));
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
        List<ChatMessageEntity> messages = messageReadService.getMessagesByCursor(roomId, before, size);

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
            @RequestBody Map<String, String> request,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Username", required = false) String username) {
        String password = request.get("password");
        boolean valid = chatRoomService.verifyRoomPassword(roomId, password);
        if (valid) {
            // Seed membership so subsequent member-gated endpoints work.
            if (userId != null && !userId.isBlank()) {
                chatRoomService.addMemberIfAbsent(roomId, userId, username);
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
        if (participantService.isRoomFull(roomId)) {
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
        // 방장만 삭제 가능. createdBy null인 레거시 방은 누구도 삭제 불가 (운영자 DB 직접 정리)
        if (room.getCreatedBy() == null || !room.getCreatedBy().equals(userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("채팅방 삭제 권한이 없습니다. 방장만 삭제할 수 있습니다."));
        }
        chatRoomService.deleteRoom(id);
        return ResponseEntity.ok(ApiResponse.ok(null, "채팅방이 삭제되었습니다."));
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

    /**
     * DM 방 per-user soft-hide. 본인 화면에서만 숨김 처리.
     * 상대가 새 메시지 보내면 자동 재출현 (lastMessageAt > hidden_at).
     * 단체방/HANDOFF는 hide 불가 -- 기존 leave 사용.
     */
    @PostMapping("/{roomId}/hide")
    public ResponseEntity<ApiResponse<Void>> hideRoom(
            @PathVariable String roomId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Username", required = false) String username) {
        if (userId == null || userId.isBlank()) {
            auditService.logAccess("unknown", "unknown", roomId, AuditEvent.ROOM_HIDE_DENIED);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("인증이 필요합니다."));
        }
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

    @GetMapping("/{roomId}/readers")
    public ResponseEntity<ApiResponse<Map<String, String>>> getRoomReaders(
            @PathVariable String roomId) {
        Map<String, String> positions = readReceiptService.getRoomReadPositions(roomId);
        return ResponseEntity.ok(ApiResponse.ok(positions));
    }

    @PostMapping("/dm")
    public ResponseEntity<ApiResponse<ChatRoom>> createDm(
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Username", required = false) String username) {
        String targetUserId = body.get("targetUserId");
        String targetUsername = body.get("targetUsername");
        if (userId == null || targetUserId == null || targetUsername == null) {
            return ResponseEntity.badRequest().body(ApiResponse.error("userId, targetUserId, targetUsername이 필요합니다."));
        }
        ChatRoom dm = dmRoomService.createOrFindDmRoom(userId, username, targetUserId, targetUsername);
        // Seed both DM participants — they may both want to call member-gated
        // endpoints without sending a STOMP message first.
        chatRoomService.addMemberIfAbsent(dm.getId(), userId, username);
        chatRoomService.addMemberIfAbsent(dm.getId(), targetUserId, targetUsername);
        return ResponseEntity.ok(ApiResponse.ok(dm));
    }

    @PutMapping("/{roomId}/settings")
    public ResponseEntity<ApiResponse<Boolean>> updateRoomSettings(
            @PathVariable String roomId,
            @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(ApiResponse.ok(
                chatRoomService.updateRoomSettings(roomId, body.get("name"), body.get("description"))));
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
            // 메시지가 아직 로드되지 않은 방 입장 시점에도 unread count를 초기화하도록 readAt만 갱신
            readReceiptService.updateReadAt(roomId, userId);
            return ResponseEntity.ok(ApiResponse.ok(null));
        }
        readReceiptService.markRead(roomId, userId, username != null ? username : "", lastReadMessageId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    /**
     * REST fallback for sending a message when STOMP is disconnected.
     * Also used for forwarded messages with forwardedFrom metadata.
     */
    @PostMapping("/{roomId}/messages")
    public ResponseEntity<ApiResponse<Void>> sendMessage(
            @PathVariable String roomId,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Username", required = false) String username) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("인증이 필요합니다."));
        }
        String content = body.get("content");
        if (content == null || content.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("content가 필요합니다."));
        }
        ChatMessage msg = new ChatMessage();
        msg.setChatRoomId(roomId);
        msg.setUserId(userId);
        msg.setUsername(username != null ? username : userId);
        msg.setContent(content);
        msg.setType(ChatMessage.MessageType.CHAT);
        String priority = body.get("priority");
        msg.setPriority(priority != null && !priority.isBlank() ? priority : "ROUTINE");
        String parentMessageId = body.get("parentMessageId");
        if (parentMessageId != null && !parentMessageId.isBlank()) {
            msg.setParentMessageId(parentMessageId);
        }
        String forwardedFrom = body.get("forwardedFrom");
        if (forwardedFrom != null && !forwardedFrom.isBlank()) {
            msg.setForwardedFrom(forwardedFrom);
        }
        String fileUrl = body.get("fileUrl");
        if (fileUrl != null && !fileUrl.isBlank()) {
            msg.setFileUrl(fileUrl);
            msg.setFileName(body.get("fileName"));
            msg.setFileContentType(body.get("fileContentType"));
        }
        messageSenderService.send(msg);
        return ResponseEntity.ok(ApiResponse.ok(null, "메시지를 전송했습니다."));
    }

    /**
     * 초대 링크 생성. 24시간 유효한 토큰을 발급하고 초대 URL을 반환한다.
     * POST /api/chat/rooms/{roomId}/invite-link
     */
    @PostMapping("/{roomId}/invite-link")
    public ResponseEntity<ApiResponse<Map<String, String>>> createInviteLink(
            @PathVariable String roomId,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("인증이 필요합니다."));
        }
        ChatRoom room = chatRoomService.getRoom(roomId).orElse(null);
        if (room == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("채팅방을 찾을 수 없습니다."));
        }
        String token = inviteLinkService.createInviteToken(roomId);
        String url = inviteLinkService.getInviteUrl(token);
        Map<String, String> data = new LinkedHashMap<>();
        data.put("token", token);
        data.put("url", url);
        return ResponseEntity.ok(ApiResponse.ok(data));
    }

    /**
     * 초대 링크로 채팅방 참가. 토큰을 검증하고 대상 채팅방 정보를 반환한다.
     * POST /api/chat/rooms/join-by-invite
     * Body: {"token": "uuid"}
     */
    @PostMapping("/join-by-invite")
    public ResponseEntity<ApiResponse<Map<String, String>>> joinByInvite(
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Username", required = false) String username) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("인증이 필요합니다."));
        }
        String token = body.get("token");
        if (token == null || token.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("token이 필요합니다."));
        }
        String roomId = inviteLinkService.resolveToken(token);
        if (roomId == null) {
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(ApiResponse.error("초대 링크가 만료되었거나 유효하지 않습니다."));
        }
        ChatRoom room = chatRoomService.getRoom(roomId).orElse(null);
        if (room == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("채팅방을 찾을 수 없습니다."));
        }
        if (participantService.isRoomFull(roomId)) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("채팅방이 만석입니다 (최대 10명)."));
        }
        // Seed membership — invite link is a valid access grant.
        chatRoomService.addMemberIfAbsent(room.getId(), userId, username);
        Map<String, String> data = new LinkedHashMap<>();
        data.put("roomId", room.getId());
        data.put("roomName", room.getName());
        return ResponseEntity.ok(ApiResponse.ok(data));
    }

    public record GetOrCreateRequest(String externalId, String name, String description) {}
}
