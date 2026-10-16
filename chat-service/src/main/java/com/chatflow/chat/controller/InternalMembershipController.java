package com.chatflow.chat.controller;

import com.chatflow.chat.auth.InternalSecretVerifier;
import com.chatflow.chat.exception.ForbiddenException;
import com.chatflow.chat.repository.RoomMemberRepository;
import com.chatflow.chat.service.room.RoomMembershipChecker;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Service-to-service room-membership lookups for search-service and
 * ai-summary-service. Not routed by the gateway; guarded by the shared
 * internal secret ({@code X-Gateway-Secret}).
 */
@RestController
@RequestMapping("/internal/rooms")
@RequiredArgsConstructor
public class InternalMembershipController {

    private final InternalSecretVerifier internalSecretVerifier;
    private final RoomMemberRepository roomMemberRepository;
    private final RoomMembershipChecker roomMembershipChecker;

    @GetMapping("/members/{userId}/room-ids")
    public ResponseEntity<List<String>> memberRoomIds(
            @RequestHeader(value = InternalSecretVerifier.HEADER, required = false) String secret,
            @PathVariable String userId) {
        requireInternalCaller(secret);
        return ResponseEntity.ok(roomMemberRepository.findRoomIdsByUserId(userId));
    }

    @GetMapping("/{roomId}/members/{userId}")
    public ResponseEntity<Void> membership(
            @RequestHeader(value = InternalSecretVerifier.HEADER, required = false) String secret,
            @PathVariable String roomId,
            @PathVariable String userId) {
        requireInternalCaller(secret);
        return roomMembershipChecker.isMember(roomId, userId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    private void requireInternalCaller(String secret) {
        if (!internalSecretVerifier.isValid(secret)) {
            throw new ForbiddenException("내부 호출 인증 실패");
        }
    }
}
