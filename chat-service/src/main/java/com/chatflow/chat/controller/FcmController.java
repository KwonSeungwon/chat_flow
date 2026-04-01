package com.chatflow.chat.controller;

import com.chatflow.chat.service.FcmNotificationService;
import com.chatflow.common.dto.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

    @PostMapping("/subscribe")
    public ResponseEntity<ApiResponse<Void>> subscribe(@Valid @RequestBody SubscribeRequest req) {
        fcmNotificationService.subscribeToRoom(req.getToken(), req.getRoomId());
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @DeleteMapping("/subscribe")
    public ResponseEntity<ApiResponse<Void>> unsubscribe(@Valid @RequestBody SubscribeRequest req) {
        fcmNotificationService.unsubscribeFromRoom(req.getToken(), req.getRoomId());
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<Boolean>> status() {
        return ResponseEntity.ok(ApiResponse.ok(fcmNotificationService.isEnabled()));
    }

    @Data
    public static class SubscribeRequest {
        @NotBlank private String token;
        @NotBlank private String roomId;
    }
}
