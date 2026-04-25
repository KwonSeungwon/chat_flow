package com.chatflow.chat.service;

import com.chatflow.chat.repository.ChatMessageRepository;
import com.chatflow.chat.repository.ChatRoomRepository;
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
    public boolean pinMessage(String roomId, String messageId) {
        return chatRoomRepository.findById(roomId).map(room -> {
            room.setPinnedMessageId(messageId);
            chatRoomRepository.save(room);
            chatMessageRepository.findById(messageId).ifPresent(msg -> {
                msg.setPinned(true);
                chatMessageRepository.save(msg);
            });
            roomCacheEvictor.evict(roomId);
            Map<String, Object> broadcast = new LinkedHashMap<>();
            broadcast.put("type", "MESSAGE_PINNED");
            broadcast.put("messageId", messageId);
            broadcast.put("chatRoomId", roomId);
            messagingTemplate.convertAndSend("/topic/chat/" + roomId, broadcast);
            return true;
        }).orElse(false);
    }

    @Transactional
    public boolean unpinMessage(String roomId) {
        return chatRoomRepository.findById(roomId).map(room -> {
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
            return true;
        }).orElse(false);
    }

}
