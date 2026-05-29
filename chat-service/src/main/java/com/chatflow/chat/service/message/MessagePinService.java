package com.chatflow.chat.service.message;

import com.chatflow.chat.repository.ChatMessageRepository;
import com.chatflow.chat.repository.ChatRoomRepository;
import com.chatflow.chat.result.ChatErrorCode;
import com.chatflow.chat.result.Result;
import com.chatflow.chat.service.RoomCacheEvictor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 메시지 고정(pin) / 해제(unpin) 서비스.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessagePinService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final RoomCacheEvictor roomCacheEvictor;

    @Transactional
    public Result<Void, ChatErrorCode> pinMessage(String roomId, String messageId) {
        return chatRoomRepository.findById(roomId).<Result<Void, ChatErrorCode>>map(room -> {
            // Validate the message exists AND belongs to this room before
            // pinning. Without this, a stale or attacker-supplied messageId
            // would land in chat_rooms.pinned_message_id, breaking the UI.
            var msgOpt = chatMessageRepository.findById(messageId)
                    .filter(m -> roomId.equals(m.getChatRoomId()) && !m.isDeleted());
            if (msgOpt.isEmpty()) {
                return Result.<Void, ChatErrorCode>err(ChatErrorCode.NOT_FOUND, "고정할 메시지를 찾을 수 없습니다.");
            }
            room.setPinnedMessageId(messageId);
            chatRoomRepository.save(room);
            msgOpt.ifPresent(msg -> {
                msg.setPinned(true);
                chatMessageRepository.save(msg);
            });
            roomCacheEvictor.evict(roomId);
            Map<String, Object> broadcast = new LinkedHashMap<>();
            broadcast.put("type", "MESSAGE_PINNED");
            broadcast.put("messageId", messageId);
            broadcast.put("chatRoomId", roomId);
            messagingTemplate.convertAndSend("/topic/chat/" + roomId, broadcast);
            return Result.<ChatErrorCode>ok();
        }).orElse(Result.err(ChatErrorCode.NOT_FOUND, "채팅방을 찾을 수 없습니다."));
    }

    @Transactional
    public Result<Void, ChatErrorCode> unpinMessage(String roomId) {
        return chatRoomRepository.findById(roomId).<Result<Void, ChatErrorCode>>map(room -> {
            String oldPin = room.getPinnedMessageId();
            room.setPinnedMessageId(null);
            chatRoomRepository.save(room);
            if (oldPin != null) {
                chatMessageRepository.findById(oldPin).ifPresent(msg -> {
                    msg.setPinned(false);
                    chatMessageRepository.save(msg);
                });
            }
            roomCacheEvictor.evict(roomId);
            Map<String, Object> broadcast = new LinkedHashMap<>();
            broadcast.put("type", "MESSAGE_UNPINNED");
            broadcast.put("chatRoomId", roomId);
            messagingTemplate.convertAndSend("/topic/chat/" + roomId, broadcast);
            return Result.<ChatErrorCode>ok();
        }).orElse(Result.err(ChatErrorCode.NOT_FOUND, "채팅방을 찾을 수 없습니다."));
    }

}
