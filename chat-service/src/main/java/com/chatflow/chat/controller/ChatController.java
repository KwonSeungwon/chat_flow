package com.chatflow.chat.controller;

import com.chatflow.chat.repository.ChatRoomRepository;
import com.chatflow.chat.repository.RoomMemberRepository;
import com.chatflow.chat.service.ChatService;
import com.chatflow.chat.service.ReadReceiptService;
import com.chatflow.common.dto.ChatMessage;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Controller
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final ReadReceiptService readReceiptService;
    private final Validator validator;
    private final SimpMessagingTemplate messagingTemplate;
    private final RoomMemberRepository roomMemberRepository;
    private final ChatRoomRepository chatRoomRepository;

    /**
     * STOMP-level membership check. Mirrors ChatRoomController.requireMember
     * semantics: true if user is a row in room_members OR is the room creator
     * (legacy bridge for pre-seed rooms). The session is authenticated by the
     * gateway, but session alone says nothing about which rooms the user may
     * touch — without this check, an authenticated user can send messages,
     * mark-read, or broadcast typing into ANY room they know the id of.
     */
    private boolean isMember(String roomId, String userId) {
        if (roomId == null || userId == null || userId.isBlank()) return false;
        if (roomMemberRepository.existsByRoomIdAndUserId(roomId, userId)) return true;
        return chatRoomRepository.findById(roomId)
                .map(r -> userId.equals(r.getCreatedBy()))
                .orElse(false);
    }

    private void rejectNonMember(String userId, String roomId, String op) {
        log.warn("STOMP {} rejected: non-member user={} room={}", op, userId, roomId);
        if (userId != null && !userId.isBlank()) {
            messagingTemplate.convertAndSendToUser(userId, "/queue/errors",
                    Map.of("type", "NOT_A_MEMBER", "operation", op, "roomId",
                            roomId != null ? roomId : ""));
        }
    }

    @MessageMapping("/chat.sendMessage")
    public void sendMessage(@Payload ChatMessage chatMessage,
                           SimpMessageHeaderAccessor headerAccessor) {
        // 세션에서 인증된 사용자 정보로 덮어쓰기 (클라이언트 위조 방지)
        Map<String, Object> sessionAttrs = headerAccessor.getSessionAttributes();
        if (sessionAttrs != null) {
            String sessionUsername = (String) sessionAttrs.get("username");
            String sessionUserId = (String) sessionAttrs.get("userId");
            if (sessionUsername != null) chatMessage.setUsername(sessionUsername);
            if (sessionUserId != null) chatMessage.setUserId(sessionUserId);
        }
        // Membership gate — without this, an authenticated user could send
        // messages into ANY room id by spoofing the chatRoomId field.
        if (!isMember(chatMessage.getChatRoomId(), chatMessage.getUserId())) {
            rejectNonMember(chatMessage.getUserId(), chatMessage.getChatRoomId(), "sendMessage");
            return;
        }
        Set<ConstraintViolation<ChatMessage>> violations = validator.validate(chatMessage);
        if (!violations.isEmpty()) {
            String errors = violations.stream()
                    .map(ConstraintViolation::getMessage)
                    .collect(Collectors.joining(", "));
            log.warn("메시지 유효성 검증 실패: {}", errors);
            if (chatMessage.getChatRoomId() != null) {
                messagingTemplate.convertAndSend(
                        "/topic/chat/" + chatMessage.getChatRoomId() + "/errors",
                        java.util.Map.of("error", errors, "timestamp", java.time.LocalDateTime.now().toString()));
            }
            return;
        }
        log.debug("Received message: {}", chatMessage);
        chatService.processMessage(chatMessage);
    }

    @MessageMapping("/chat.addUser")
    public void addUser(@Payload ChatMessage chatMessage,
                       SimpMessageHeaderAccessor headerAccessor) {
        Map<String, Object> sessionAttrs = headerAccessor.getSessionAttributes();
        // 핸드셰이크 시 Gateway가 주입한 인증 정보를 우선 사용 (클라이언트 위조 방지)
        if (sessionAttrs != null) {
            String verifiedUserId = (String) sessionAttrs.get("userId");
            String verifiedUsername = (String) sessionAttrs.get("username");
            if (verifiedUserId != null) {
                chatMessage.setUserId(verifiedUserId);
                chatMessage.setUsername(verifiedUsername);
            }
            sessionAttrs.put("chatRoomId", chatMessage.getChatRoomId());
        }
        String sessionId = headerAccessor.getSessionId();
        chatService.addUser(chatMessage, sessionId);
    }

    @MessageMapping("/chat.typing")
    public void typing(@Payload Map<String, String> payload,
                       SimpMessageHeaderAccessor headerAccessor) {
        String roomId = payload.get("chatRoomId");
        if (roomId == null) return;
        Map<String, Object> sessionAttrs = headerAccessor.getSessionAttributes();
        String userId = (sessionAttrs != null) ? (String) sessionAttrs.get("userId") : null;
        // Membership gate — non-member could inject fake "X is typing..." spam.
        if (!isMember(roomId, userId)) {
            rejectNonMember(userId, roomId, "typing");
            return;
        }
        // 세션 username 우선, null/blank이면 payload로 fallback — 빈 username 브로드캐스트 방지
        String username = (sessionAttrs != null) ? (String) sessionAttrs.get("username") : null;
        if (username == null || username.isBlank()) {
            username = payload.get("username");
        }
        if (username == null || username.isBlank()) return;  // 신원 확인 불가 시 브로드캐스트 생략
        messagingTemplate.convertAndSend("/topic/chat/" + roomId + "/typing",
                Map.of("username", username, "timestamp", java.time.LocalDateTime.now().toString()));
    }

    @MessageMapping("/chat.markRead")
    public void markRead(@Payload Map<String, String> payload,
                         SimpMessageHeaderAccessor headerAccessor) {
        String roomId = payload.get("roomId");
        String lastReadMessageId = payload.get("lastReadMessageId");
        if (roomId == null || lastReadMessageId == null) {
            log.warn("markRead: roomId 또는 lastReadMessageId 누락");
            return;
        }
        Map<String, Object> sessionAttrs = headerAccessor.getSessionAttributes();
        String userId = sessionAttrs != null ? (String) sessionAttrs.get("userId") : null;
        String username = sessionAttrs != null ? (String) sessionAttrs.get("username") : null;
        if (userId == null) {
            log.warn("markRead: 세션에 userId 없음 — 거부 (roomId={})", roomId);
            return;
        }
        // Membership gate — non-member could corrupt others' read-state UI by
        // broadcasting fake read-receipts.
        if (!isMember(roomId, userId)) {
            rejectNonMember(userId, roomId, "markRead");
            return;
        }
        readReceiptService.markRead(roomId, userId, username, lastReadMessageId);
    }
}