package com.chatflow.chat.controller;

import com.chatflow.chat.entity.ChatRoom;
import com.chatflow.chat.service.ChatRoomService;
import com.chatflow.chat.service.ReadReceiptService;
import com.chatflow.chat.service.UnreadCountService;
import com.chatflow.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/chat/rooms")
@RequiredArgsConstructor
public class RoomReadStateController {

    private final ChatRoomService chatRoomService;
    private final UnreadCountService unreadCountService;
    private final ReadReceiptService readReceiptService;
    private final StringRedisTemplate redisTemplate;
    private final RoomMembershipGuard membershipGuard;

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

    @GetMapping("/{roomId}/readers")
    public ResponseEntity<?> getRoomReaders(
            @PathVariable String roomId,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        ResponseEntity<ApiResponse<?>> gate = membershipGuard.requireMember(roomId, userId);
        if (gate != null) return gate;
        Map<String, String> positions = readReceiptService.getRoomReadPositions(roomId);
        return ResponseEntity.ok(ApiResponse.ok(positions));
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
    public ResponseEntity<?> updateLastRead(
            @PathVariable String roomId,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Username", required = false) String username) {
        ResponseEntity<ApiResponse<?>> gate = membershipGuard.requireMember(roomId, userId);
        if (gate != null) return gate;
        String lastReadMessageId = body.get("lastReadMessageId");
        if (lastReadMessageId == null || lastReadMessageId.isBlank()) {
            // 메시지가 아직 로드되지 않은 방 입장 시점에도 unread count를 초기화하도록 readAt만 갱신
            readReceiptService.updateReadAt(roomId, userId);
            return ResponseEntity.ok(ApiResponse.ok(null));
        }
        readReceiptService.markRead(roomId, userId, username != null ? username : "", lastReadMessageId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
