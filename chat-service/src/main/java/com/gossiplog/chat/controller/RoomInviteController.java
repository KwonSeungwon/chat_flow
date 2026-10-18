package com.gossiplog.chat.controller;

import com.gossiplog.chat.auth.AuthenticatedUser;
import com.gossiplog.chat.auth.RequireAuth;
import com.gossiplog.chat.auth.RequireMember;
import com.gossiplog.chat.entity.ChatRoom;
import com.gossiplog.chat.service.room.ChatRoomService;
import com.gossiplog.chat.service.room.InviteLinkService;
import com.gossiplog.chat.service.room.ParticipantService;
import com.gossiplog.chat.service.room.RoomAccessPolicy;
import com.gossiplog.chat.service.room.RoomMembershipService;
import com.gossiplog.chat.result.ChatErrorCode;
import com.gossiplog.chat.result.ErrorResponses;
import com.gossiplog.chat.result.Result;
import com.gossiplog.common.dto.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
    private final RoomMembershipService roomMembershipService;
    private final InviteLinkService inviteLinkService;
    private final ParticipantService participantService;
    private final StringRedisTemplate redisTemplate;

    @RequireMember
    @PostMapping("/{roomId}/invite")
    public ResponseEntity<ApiResponse<Void>> inviteUser(
            @PathVariable String roomId,
            @Valid @RequestBody InviteRequest request,
            @AuthenticatedUser String inviterId,
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
        // 이미 참여 중인 멤버 중복 초대 방지
        String participantKey = "gossiplog:room:participants:" + roomId;
        Set<String> members = redisTemplate.opsForSet().members(participantKey);
        if (members != null) {
            final String target = request.targetUsername().toLowerCase();
            boolean alreadyPresent = members.stream()
                    .anyMatch(e -> e.toLowerCase().endsWith(":" + target));
            if (alreadyPresent) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error(request.targetUsername() + "님은 이미 채팅방에 참여 중입니다."));
            }
        }
        roomMembershipService.sendInviteMessage(roomId, inviterName, request.targetUsername());
        return ResponseEntity.ok(ApiResponse.ok(null, "초대 메시지를 보냈습니다."));
    }

    /**
     * 초대 링크 생성. 24시간 유효한 토큰을 발급하고 초대 URL을 반환한다.
     * POST /api/chat/rooms/{roomId}/invite-link
     */
    @RequireMember
    @PostMapping("/{roomId}/invite-link")
    public ResponseEntity<?> createInviteLink(
            @PathVariable String roomId,
            @AuthenticatedUser String userId) {
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
    @RequireAuth
    @PostMapping("/join-by-invite")
    public ResponseEntity<?> joinByInvite(
            @Valid @RequestBody JoinByInviteRequest request,
            @AuthenticatedUser String userId,
            @RequestHeader(value = "X-Username", required = false) String username) {
        Result<String, ChatErrorCode> resolved = inviteLinkService.resolveToken(request.token());
        if (resolved.isFailure()) {
            return ErrorResponses.from(resolved);
        }
        String roomId = resolved.value();
        ChatRoom room = chatRoomService.getRoom(roomId).orElse(null);
        if (room == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("채팅방을 찾을 수 없습니다."));
        }
        // Seed membership — invite link is a valid access grant, but the policy
        // still refuses banned users, DMs and full rooms.
        RoomAccessPolicy.JoinDecision decision = roomMembershipService.grantMembership(
                room.getId(), userId, username, RoomAccessPolicy.GrantPath.INVITE);
        if (!decision.allowed()) {
            HttpStatus status = switch (decision.reason()) {
                case ROOM_NOT_FOUND -> HttpStatus.NOT_FOUND;
                case ROOM_FULL -> HttpStatus.BAD_REQUEST;
                default -> HttpStatus.FORBIDDEN;
            };
            return ResponseEntity.status(status)
                    .body(ApiResponse.error(ChatRoomController.denialMessage(decision.reason())));
        }
        Map<String, String> data = new LinkedHashMap<>();
        data.put("roomId", room.getId());
        data.put("roomName", room.getName());
        return ResponseEntity.ok(ApiResponse.ok(data));
    }

    // ── Request records ─────────────────────────────────────────

    public record InviteRequest(
            @NotBlank(message = "targetUsername이 필요합니다")
            @Size(max = 50, message = "targetUsername은 50자를 초과할 수 없습니다")
            String targetUsername
    ) {}

    public record JoinByInviteRequest(
            @NotBlank(message = "token이 필요합니다")
            String token
    ) {}
}
