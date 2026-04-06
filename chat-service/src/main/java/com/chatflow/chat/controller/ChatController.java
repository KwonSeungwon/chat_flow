package com.chatflow.chat.controller;

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
        headerAccessor.getSessionAttributes().put("username", chatMessage.getUsername());
        headerAccessor.getSessionAttributes().put("chatRoomId", chatMessage.getChatRoomId());
        chatService.addUser(chatMessage);
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
        if (userId == null) userId = payload.get("userId");
        if (username == null) username = payload.get("username");
        readReceiptService.markRead(roomId, userId, username, lastReadMessageId);
    }
}