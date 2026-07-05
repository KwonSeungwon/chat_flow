package com.chatflow.chat.service;

import com.chatflow.chat.entity.RoomMemberEntity;
import com.chatflow.chat.service.message.MessageSenderService;
import com.chatflow.common.dto.ChatMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 채팅 Facade — 메시지 전송과 사용자 입퇴장을 각 전문 서비스로 위임.
 * ChatController, WebSocketEventListener가 이 클래스를 주입받아 사용.
 */
@Service
@RequiredArgsConstructor
public class ChatService {

    private final MessageSenderService messageSenderService;
    private final UserPresenceService userPresenceService;

    public void processMessage(ChatMessage message) {
        messageSenderService.send(message);
    }

    /**
     * Processes a message with a pre-resolved room member entity, avoiding a
     * duplicate room_members lookup in the mute gate.
     *
     * @param resolvedMember the member entity fetched during the membership check,
     *                       or {@code null} for legacy creator-only membership.
     */
    public void processMessage(ChatMessage message, RoomMemberEntity resolvedMember) {
        messageSenderService.send(message, resolvedMember);
    }

    public void addUser(ChatMessage message) {
        userPresenceService.join(message);
    }

    public void addUser(ChatMessage message, String sessionId) {
        userPresenceService.join(message, sessionId);
    }

    public void removeUser(String roomId, String username) {
        userPresenceService.leave(roomId, username);
    }

    public void removeUser(String roomId, String username, String sessionId) {
        userPresenceService.leave(roomId, username, sessionId);
    }
}
