package com.chatflow.chat.controller;

import com.chatflow.chat.dto.BanDto;
import com.chatflow.chat.dto.BanRequest;
import com.chatflow.chat.entity.RoomBanEntity;
import com.chatflow.chat.repository.RoomMemberRepository;
import com.chatflow.chat.service.RoomBanService;
import com.chatflow.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/chat/rooms/{roomId}/bans")
@RequiredArgsConstructor
public class RoomBanController {

    private final RoomBanService roomBanService;
    private final RoomMemberRepository roomMemberRepository;

    /**
     * GET /api/chat/rooms/{roomId}/bans
     * Lists all bans for the room. OWNER or MOD only (enforced in service).
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<BanDto>>> listBans(
            @PathVariable String roomId,
            @RequestHeader(value = "X-User-Id", required = true) String callerUserId) {
        List<RoomBanEntity> bans = roomBanService.listBans(roomId, callerUserId);

        List<BanDto> banDtos = bans.stream()
                .map(ban -> {
                    String bannedByUsername = roomMemberRepository
                            .findByRoomIdAndUserId(roomId, ban.getBannedBy())
                            .map(m -> m.getUsername())
                            .orElse("(unknown)");
                    return BanDto.from(ban, bannedByUsername);
                })
                .toList();

        return ResponseEntity.ok(ApiResponse.ok(banDtos));
    }

    /**
     * POST /api/chat/rooms/{roomId}/bans
     * Bans a user (kick + ban). OWNER or MOD only.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<BanDto>> banUser(
            @PathVariable String roomId,
            @RequestBody BanRequest request,
            @RequestHeader(value = "X-User-Id", required = true) String callerUserId) {
        if (request.userId() == null || request.userId().isBlank()) {
            throw new IllegalArgumentException("userId는 필수입니다.");
        }

        roomBanService.banUser(roomId, callerUserId, request.userId(), request.reason());
        log.info("User banned: roomId={}, target={}, by={}", roomId, request.userId(), callerUserId);

        // Retrieve the ban entity to return in response
        RoomBanEntity banEntity = roomBanService.listBans(roomId, callerUserId).stream()
                .filter(b -> b.getUserId().equals(request.userId()))
                .findFirst()
                .orElse(null);

        String bannedByUsername = roomMemberRepository
                .findByRoomIdAndUserId(roomId, callerUserId)
                .map(m -> m.getUsername())
                .orElse("(unknown)");

        BanDto banDto = banEntity != null
                ? BanDto.from(banEntity, bannedByUsername)
                : new BanDto(request.userId(), null, bannedByUsername, request.reason(), null);

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(banDto));
    }

    /**
     * DELETE /api/chat/rooms/{roomId}/bans/{userId}
     * Unbans a user. OWNER or MOD only.
     */
    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> unbanUser(
            @PathVariable String roomId,
            @PathVariable String userId,
            @RequestHeader(value = "X-User-Id", required = true) String callerUserId) {
        roomBanService.unbanUser(roomId, callerUserId, userId);
        log.info("User unbanned: roomId={}, target={}, by={}", roomId, userId, callerUserId);
        return ResponseEntity.noContent().build();
    }
}
