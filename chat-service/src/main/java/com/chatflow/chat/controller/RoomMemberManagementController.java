package com.chatflow.chat.controller;

import com.chatflow.chat.dto.MemberDto;
import com.chatflow.chat.dto.MuteRequest;
import com.chatflow.chat.dto.MuteResponse;
import com.chatflow.chat.dto.RoleChangeRequest;
import com.chatflow.chat.entity.RoomRole;
import com.chatflow.chat.repository.RoomMemberRepository;
import com.chatflow.chat.service.MemberManagementService;
import com.chatflow.chat.service.MuteResult;
import com.chatflow.chat.service.RoomPermissionService;
import com.chatflow.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/chat/rooms/{roomId}")
@RequiredArgsConstructor
public class RoomMemberManagementController {

    private final RoomMemberRepository roomMemberRepository;
    private final MemberManagementService memberManagementService;
    private final RoomPermissionService roomPermissionService;

    /**
     * GET /api/chat/rooms/{roomId}/members
     * Returns the member list. Any room member can call this.
     */
    @GetMapping("/members")
    public ResponseEntity<ApiResponse<List<MemberDto>>> getMembers(
            @PathVariable String roomId,
            @RequestHeader(value = "X-User-Id", required = true) String callerUserId) {
        // Verify caller is a member (any role)
        roomPermissionService.requireRole(roomId, callerUserId,
                RoomRole.OWNER, RoomRole.MODERATOR, RoomRole.MEMBER);

        List<MemberDto> members = roomMemberRepository.findByRoomId(roomId).stream()
                .map(MemberDto::from)
                .toList();

        return ResponseEntity.ok(ApiResponse.ok(members));
    }

    /**
     * PATCH /api/chat/rooms/{roomId}/members/{userId}/role
     * Changes a member's role. OWNER only.
     * If role == "OWNER", delegates to transferOwnership.
     */
    @PatchMapping("/members/{userId}/role")
    public ResponseEntity<ApiResponse<Void>> changeRole(
            @PathVariable String roomId,
            @PathVariable String userId,
            @RequestBody RoleChangeRequest request,
            @RequestHeader(value = "X-User-Id", required = true) String callerUserId) {
        if (request.role() == null || request.role().isBlank()) {
            throw new IllegalArgumentException("role은 필수입니다.");
        }

        RoomRole newRole = RoomRole.valueOf(request.role().toUpperCase());

        if (newRole == RoomRole.OWNER) {
            memberManagementService.transferOwnership(roomId, callerUserId, userId);
            log.info("Ownership transferred: roomId={}, from={}, to={}", roomId, callerUserId, userId);
        } else {
            memberManagementService.changeRole(roomId, callerUserId, userId, newRole);
            log.info("Role changed: roomId={}, target={}, newRole={}, by={}",
                    roomId, userId, newRole, callerUserId);
        }

        return ResponseEntity.ok(ApiResponse.ok(null, "역할이 변경되었습니다."));
    }

    /**
     * DELETE /api/chat/rooms/{roomId}/members/{userId}
     * Kicks a member. OWNER or MOD only.
     */
    @DeleteMapping("/members/{userId}")
    public ResponseEntity<Void> kickMember(
            @PathVariable String roomId,
            @PathVariable String userId,
            @RequestHeader(value = "X-User-Id", required = true) String callerUserId) {
        memberManagementService.kickMember(roomId, callerUserId, userId);
        log.info("Member kicked: roomId={}, target={}, by={}", roomId, userId, callerUserId);
        return ResponseEntity.noContent().build();
    }

    /**
     * POST /api/chat/rooms/{roomId}/members/{userId}/mute
     * Mutes a member. OWNER or MOD only.
     */
    @PostMapping("/members/{userId}/mute")
    public ResponseEntity<ApiResponse<MuteResponse>> muteMember(
            @PathVariable String roomId,
            @PathVariable String userId,
            @RequestBody MuteRequest request,
            @RequestHeader(value = "X-User-Id", required = true) String callerUserId) {
        MuteResult result = memberManagementService.muteMember(
                roomId, callerUserId, userId, request.minutes());
        log.info("Member muted: roomId={}, target={}, minutes={}, by={}",
                roomId, userId, request.minutes(), callerUserId);
        return ResponseEntity.ok(ApiResponse.ok(new MuteResponse(result.mutedUntil())));
    }

    /**
     * DELETE /api/chat/rooms/{roomId}/members/{userId}/mute
     * Unmutes a member. OWNER or MOD only.
     */
    @DeleteMapping("/members/{userId}/mute")
    public ResponseEntity<Void> unmuteMember(
            @PathVariable String roomId,
            @PathVariable String userId,
            @RequestHeader(value = "X-User-Id", required = true) String callerUserId) {
        memberManagementService.unmuteMember(roomId, callerUserId, userId);
        log.info("Member unmuted: roomId={}, target={}, by={}", roomId, userId, callerUserId);
        return ResponseEntity.noContent().build();
    }
}
