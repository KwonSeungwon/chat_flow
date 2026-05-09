package com.chatflow.chat.service;

import com.chatflow.chat.entity.ChatMessageEntity;
import com.chatflow.chat.repository.ChatMessageRepository;
import com.chatflow.common.dto.BaseMessage.MessageType;
import com.chatflow.common.dto.ChatMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MessageThreadService {

    private final ChatMessageRepository chatMessageRepository;

    @Transactional(readOnly = true)
    public List<ChatMessage> findReplies(String parentMessageId) {
        return chatMessageRepository
            .findByParentMessageIdOrderByTimestampAsc(parentMessageId)
            .stream()
            .filter(e -> !e.isDeleted())
            .map(this::toDto)
            .toList();
    }

    private ChatMessage toDto(ChatMessageEntity e) {
        return ChatMessage.builder()
            .messageId(e.getMessageId())
            .chatRoomId(e.getChatRoomId())
            .userId(e.getUserId())
            .username(e.getUsername())
            .content(e.getContent())
            .type(e.getType() != null ? MessageType.valueOf(e.getType()) : null)
            .timestamp(e.getTimestamp())
            .priority(e.getPriority())
            .isAiGenerated(e.isAiGenerated())
            .fileUrl(e.getFileUrl())
            .fileName(e.getFileName())
            .fileContentType(e.getFileContentType())
            .parentMessageId(e.getParentMessageId())
            .parentMessagePreview(e.getParentMessagePreview())
            .forwardedFrom(e.getForwardedFrom())
            .build();
    }
}
