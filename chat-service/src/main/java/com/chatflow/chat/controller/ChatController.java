package com.chatflow.chat.controller;

import com.chatflow.chat.dto.InboundChatCommand;
import com.chatflow.chat.service.ChatService;
import com.chatflow.chat.service.read.ReadReceiptService;
import com.chatflow.chat.service.room.RoomMembershipChecker;
import com.chatflow.chat.service.room.RoomMembershipChecker.MembershipResult;
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
import java.util.Optional;
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
    private final RoomMembershipChecker membershipChecker;

    /**
     * Delegates to {@link RoomMembershipChecker} — the single source of truth
     * for the "is this user a member of this room?" predicate, shared with the
     * STOMP SUBSCRIBE interceptor.
     */
    private boolean isMember(String roomId, String userId) {
        return membershipChecker.isMember(roomId, userId);
    }

    private void rejectNonMember(String userId, String roomId, String op) {
        log.warn("STOMP {} rejected: non-member user={} room={}", op, userId, roomId);
        if (userId != null && !userId.isBlank()) {
            messagingTemplate.convertAndSendToUser(userId, "/queue/errors",
                    Map.of("type", "NOT_A_MEMBER", "operation", op, "roomId",
                            roomId != null ? roomId : ""));
        }
    }

    /**
     * Identity is taken from the STOMP session only (handshake or CONNECT); the
     * payload type has no identity fields at all. Rejections go to the sender's
     * {@code /user/queue/errors} when there is a sender, otherwise they are
     * dropped with a log line that carries no payload content.
     */
    @MessageMapping("/chat.sendMessage")
    public void sendMessage(@Payload InboundChatCommand command,
                           SimpMessageHeaderAccessor headerAccessor) {
        String userId = sessionUserId(headerAccessor);
        if (userId == null) {
            log.warn("STOMP sendMessage rejected: no authenticated identity on session={}",
                    headerAccessor.getSessionId());
            return;
        }
        if (!command.hasAllowedType()) {
            log.warn("STOMP sendMessage rejected: type {} not user-sendable, user={} room={}",
                    command.type(), userId, command.chatRoomId());
            messagingTemplate.convertAndSendToUser(userId, "/queue/errors",
                    Map.of("type", "INVALID_MESSAGE_TYPE", "operation", "sendMessage", "roomId",
                            command.chatRoomId() != null ? command.chatRoomId() : ""));
            return;
        }
        ChatMessage chatMessage = command.toChatMessage(userId, sessionUsername(headerAccessor, userId));
        // Membership gate — without this, an authenticated user could send
        // messages into ANY room id by spoofing the chatRoomId field.
        // Uses findMember (single DB fetch) so the resolved entity can be
        // forwarded to the mute-gate in MessageSenderService, avoiding a
        // duplicate room_members lookup.
        Optional<MembershipResult> membership = membershipChecker.findMember(
                chatMessage.getChatRoomId(), userId);
        if (membership.isEmpty()) {
            rejectNonMember(userId, chatMessage.getChatRoomId(), "sendMessage");
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
        log.debug("Received message: room={} user={} type={}",
                chatMessage.getChatRoomId(), userId, chatMessage.getType());
        chatService.processMessage(chatMessage, membership.get().entity());
    }

    /** Session userId set at handshake (gateway) or CONNECT (Bearer); falls back to the Principal. */
    private static String sessionUserId(SimpMessageHeaderAccessor headerAccessor) {
        Map<String, Object> attrs = headerAccessor.getSessionAttributes();
        String userId = attrs != null ? (String) attrs.get("userId") : null;
        if ((userId == null || userId.isBlank()) && headerAccessor.getUser() != null) {
            userId = headerAccessor.getUser().getName();
        }
        return userId == null || userId.isBlank() ? null : userId;
    }

    private static String sessionUsername(SimpMessageHeaderAccessor headerAccessor, String userId) {
        Map<String, Object> attrs = headerAccessor.getSessionAttributes();
        String username = attrs != null ? (String) attrs.get("username") : null;
        return username == null || username.isBlank() ? userId : username;
    }

    @MessageMapping("/chat.addUser")
    public void addUser(@Payload ChatMessage chatMessage,
                       SimpMessageHeaderAccessor headerAccessor) {
        Map<String, Object> sessionAttrs = headerAccessor.getSessionAttributes();
        // Identity comes only from the session (gateway handshake or CONNECT Bearer).
        // A payload userId is never trusted: the join gate decides DM/private access
        // by userId, so a spoofed one would let anyone enter someone else's room.
        String verifiedUserId = sessionUserId(headerAccessor);
        chatMessage.setUserId(verifiedUserId);
        if (verifiedUserId != null) {
            chatMessage.setUsername(sessionUsername(headerAccessor, verifiedUserId));
        }
        String sessionId = headerAccessor.getSessionId();
        // Join gate (RoomAccessPolicy) runs inside the presence pipeline; a refused
        // join has already been answered on /user/queue/errors. Record the room on
        // the session only on success, and only after any full-room redirect, so
        // disconnect cleanup never emits LEAVE for a room the user never entered.
        boolean joined = chatService.addUser(chatMessage, sessionId);
        if (joined && sessionAttrs != null) {
            sessionAttrs.put("chatRoomId", chatMessage.getChatRoomId());
        }
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