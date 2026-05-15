package com.chatflow.chat.controller;

import com.chatflow.chat.entity.ChatRoom;
import com.chatflow.chat.repository.ChatRoomRepository;
import com.chatflow.chat.repository.RoomMemberRepository;
import com.chatflow.chat.service.FcmNotificationService;
import com.chatflow.common.dto.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * FCM topic subscription management.
 * Clients call subscribe when joining a room and unsubscribe when leaving.
 */
@Slf4j
@RestController
@RequestMapping("/api/fcm")
@RequiredArgsConstructor
public class FcmController {

    private final FcmNotificationService fcmNotificationService;
    private final RoomMemberRepository roomMemberRepository;
    private final ChatRoomRepository chatRoomRepository;

    /**
     * Same membership semantics as the room controllers: 401 if no userId,
     * 403 if not a member (with legacy createdBy bridge for rooms created
     * before the room_members seeding patch landed).
     */
    private ResponseEntity<ApiResponse<Void>> requireMember(String roomId, String userId) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("인증이 필요합니다."));
        }
        if (roomMemberRepository.existsByRoomIdAndUserId(roomId, userId)) return null;
        ChatRoom room = chatRoomRepository.findById(roomId).orElse(null);
        if (room != null && userId.equals(room.getCreatedBy())) return null;
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("방 멤버가 아닙니다."));
    }

    @PostMapping("/subscribe")
    public ResponseEntity<ApiResponse<Void>> subscribe(
            @Valid @RequestBody SubscribeRequest req,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        ResponseEntity<ApiResponse<Void>> gate = requireMember(req.getRoomId(), userId);
        if (gate != null) return gate;
        fcmNotificationService.subscribeToRoom(req.getToken(), req.getRoomId());
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @DeleteMapping("/subscribe")
    public ResponseEntity<ApiResponse<Void>> unsubscribe(@Valid @RequestBody SubscribeRequest req) {
        // No membership check on unsubscribe — leaving a room you're no longer
        // a member of must still stop the pushes. The token alone is sufficient
        // (Firebase verifies device ownership), and there's no information leak
        // because the call is destructive only against the caller's own pushes.
        fcmNotificationService.unsubscribeFromRoom(req.getToken(), req.getRoomId());
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PostMapping("/unsubscribe-all")
    public ResponseEntity<ApiResponse<Void>> unsubscribeAll(@Valid @RequestBody UnsubscribeAllRequest req) {
        fcmNotificationService.unsubscribeAll(req.getToken());
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<Boolean>> status() {
        return ResponseEntity.ok(ApiResponse.ok(fcmNotificationService.isEnabled()));
    }

    @Data
    public static class SubscribeRequest {
        @NotBlank @Size(min = 100, max = 300) private String token;
        @NotBlank @Pattern(regexp = "^[a-zA-Z0-9_\\-]{1,64}$") private String roomId;
    }

    @Data
    public static class UnsubscribeAllRequest {
        @NotBlank @Size(min = 100, max = 300) private String token;
    }
}
