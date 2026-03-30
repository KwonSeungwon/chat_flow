package com.chatflow.chat.service;

import com.chatflow.chat.entity.ChatRoom;
import com.chatflow.common.dto.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final SimpMessagingTemplate messagingTemplate;
    private final ChatPersistenceService chatPersistenceService;
    private final ChatRoomService chatRoomService;

    private static final String CHAT_TOPIC = "chat-messages";
    private static final String AI_SUMMARY_TOPIC = "ai-summary-requests";

    public void processMessage(ChatMessage message) {
        message.setMessageId(UUID.randomUUID().toString());
        message.setTimestamp(LocalDateTime.now());

        log.info("Processing chat message: {}", message.getMessageId());

        // WebSocket으로 실시간 브로드캐스트
        messagingTemplate.convertAndSend("/topic/chat/" + message.getChatRoomId(), message);

        // DB 저장 + Outbox 이벤트 생성
        chatPersistenceService.persistWithOutbox(message, CHAT_TOPIC, "MESSAGE_SENT");

        // AI 요약 요청
        if (shouldRequestAISummary(message)) {
            chatPersistenceService.saveOutboxEvent(message, AI_SUMMARY_TOPIC, "AI_SUMMARY_REQUEST");
        }
    }

    public void addUser(ChatMessage message) {
        // 방 인원 제한 체크 → 꽉 차면 자동 생성방으로 리다이렉트
        if (chatRoomService.isRoomFull(message.getChatRoomId())) {
            ChatRoom room = chatRoomService.getRoom(message.getChatRoomId()).orElse(null);
            String baseName = room != null ? room.getName().replaceAll("-\\d+$", "") : "일반";
            ChatRoom newRoom = chatRoomService.findOrCreateAvailableRoom(baseName);

            log.info("Room {} full, redirecting user {} to {}", message.getChatRoomId(), message.getUsername(), newRoom.getId());
            messagingTemplate.convertAndSend(
                    "/topic/chat/" + message.getChatRoomId() + "/errors",
                    java.util.Map.of("type", "ROOM_FULL", "redirectTo", newRoom.getId(), "roomName", newRoom.getName()));

            // 새 방에 입장 처리
            message.setChatRoomId(newRoom.getId());
        }

        message.setType(ChatMessage.MessageType.JOIN);
        message.setTimestamp(LocalDateTime.now());
        message.setMessageId(UUID.randomUUID().toString());
        message.setContent(message.getUsername() + "님이 입장하셨습니다.");

        log.info("User {} joined chat room {}", message.getUsername(), message.getChatRoomId());

        chatRoomService.incrementParticipantCount(message.getChatRoomId());
        messagingTemplate.convertAndSend("/topic/chat/" + message.getChatRoomId(), message);
        chatPersistenceService.saveOutboxEvent(message, CHAT_TOPIC, "USER_JOINED");
    }

    public void removeUser(String roomId, String username) {
        chatRoomService.decrementParticipantCount(roomId);
        log.info("User {} left chat room {}", username, roomId);
    }

    private boolean shouldRequestAISummary(ChatMessage message) {
        return message.getContent() != null && message.getContent().length() > 100;
    }
}
