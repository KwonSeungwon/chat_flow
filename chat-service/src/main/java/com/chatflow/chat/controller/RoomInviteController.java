package com.chatflow.chat.controller;

import com.chatflow.chat.entity.ChatRoom;
import com.chatflow.chat.service.ChatRoomService;
import com.chatflow.chat.service.InviteLinkService;
import com.chatflow.chat.service.ParticipantService;
import com.chatflow.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Slf4j
@RestController
@RequestMapping("/api/chat/rooms")
@RequiredArgsConstructor
public class RoomInviteController {

    private final ChatRoomService chatRoomService;
    private final InviteLinkService inviteLinkService;
    private final ParticipantService participantService;
    private final StringRedisTemplate redisTemplate;
    private final RoomMembershipGuard membershipGuard;

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

    /**
     * 초대 링크 생성. 24시간 유효한 토큰을 발급하고 초대 URL을 반환한다.
     * POST /api/chat/rooms/{roomId}/invite-link
     */
    @PostMapping("/{roomId}/invite-link")
    public ResponseEntity<?> createInviteLink(
            @PathVariable String roomId,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        // Only members may generate an invite token — previously this was
        // open to any authenticated user who knew the roomId, which let
        // outsiders mint join tokens for private rooms.
        ResponseEntity<ApiResponse<?>> gate = membershipGuard.requireMember(roomId, userId);
        if (gate != null) return gate;
        ChatRoom room = chatRoomService.getRoom(roomId).orElse(null);
        if (room == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("채팅방을 찾을 수 없습니다."));
        }
        if (!room.isAllowInvites()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("이 방은 초대 링크 생성이 비활성화되어 있습니다."));
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
}
