package com.chatflow.chat.controller;

import com.chatflow.chat.auth.AuthenticatedUser;
import com.chatflow.chat.auth.RequireAuth;
import com.chatflow.chat.service.FcmNotificationService;
import com.chatflow.common.dto.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private final RoomMembershipGuard membershipGuard;

    @PostMapping("/subscribe")
    @RequireAuth
    public ResponseEntity<ApiResponse<Void>> subscribe(
            @Valid @RequestBody SubscribeRequest req,
            @AuthenticatedUser String userId) {
        membershipGuard.requireMember(req.getRoomId(), userId);
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
