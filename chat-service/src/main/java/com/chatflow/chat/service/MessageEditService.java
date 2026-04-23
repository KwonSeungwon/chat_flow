package com.chatflow.chat.service;

import com.chatflow.chat.repository.ChatMessageRepository;
import com.chatflow.common.util.MessageEncryptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 메시지 삭제(soft delete) / 편집 서비스.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageEditService {

    private final ChatMessageRepository chatMessageRepository;
    private final MessageEncryptor messageEncryptor;
    private final SimpMessagingTemplate messagingTemplate;

    @Transactional
    public boolean deleteMessage(String messageId, String requestingUserId) {
        return chatMessageRepository.findById(messageId).map(entity -> {
            if (entity.getUserId() == null || !entity.getUserId().equals(requestingUserId)) {
                return false;
            }
            entity.setDeleted(true);
            entity.setContent("삭제된 메시지입니다.");
            chatMessageRepository.save(entity);
            Map<String, Object> broadcast = new LinkedHashMap<>();
            broadcast.put("type", "MESSAGE_DELETED");
            broadcast.put("messageId", messageId);
            broadcast.put("chatRoomId", entity.getChatRoomId());
            broadcast.put("content", "삭제된 메시지입니다.");
            broadcast.put("username", entity.getUsername());
            broadcast.put("timestamp", entity.getTimestamp().toString());
            messagingTemplate.convertAndSend("/topic/chat/" + entity.getChatRoomId(), broadcast);
            log.info("Message deleted: {} by user {}", messageId, requestingUserId);
            return true;
        }).orElse(false);
    }

    @Transactional
    public boolean editMessage(String messageId, String requestingUserId, String newContent) {
        return chatMessageRepository.findById(messageId).map(entity -> {
            if (entity.getUserId() == null || !entity.getUserId().equals(requestingUserId)) {
                return false;
            }
            if (entity.isDeleted()) return false;
            entity.setContent(messageEncryptor.isEnabled() ? messageEncryptor.encrypt(newContent) : newContent);
            entity.setEdited(true);
            entity.setEditedAt(LocalDateTime.now());
            chatMessageRepository.save(entity);

            Map<String, Object> broadcast = new LinkedHashMap<>();
            broadcast.put("type", "MESSAGE_EDITED");
            broadcast.put("messageId", messageId);
            broadcast.put("chatRoomId", entity.getChatRoomId());
            broadcast.put("content", newContent);
            broadcast.put("username", entity.getUsername());
            broadcast.put("timestamp", entity.getTimestamp().toString());
            broadcast.put("editedAt", entity.getEditedAt().toString());
            messagingTemplate.convertAndSend("/topic/chat/" + entity.getChatRoomId(), broadcast);
            log.info("Message edited: {} by user {}", messageId, requestingUserId);
            return true;
        }).orElse(false);
    }
}
