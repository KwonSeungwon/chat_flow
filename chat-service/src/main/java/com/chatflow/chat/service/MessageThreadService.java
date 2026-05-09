package com.chatflow.chat.service;

import com.chatflow.chat.entity.ChatMessageEntity;
import com.chatflow.chat.repository.ChatMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Read-only service for fetching the reply chain of a parent message.
 * Returns entities directly (matching the existing GET /messages list
 * endpoint pattern in ChatRoomController), so the JSON response carries
 * reactions, edited flag, editedAt, and pinned — fields that the shared
 * BaseMessage/ChatMessage DTO does not include.
 */
@Service
@RequiredArgsConstructor
public class MessageThreadService {

    private final ChatMessageRepository chatMessageRepository;

    @Transactional(readOnly = true)
    public List<ChatMessageEntity> findReplies(String chatRoomId, String parentMessageId) {
        return chatMessageRepository
            .findByChatRoomIdAndParentMessageIdAndDeletedFalseOrderByTimestampAsc(
                chatRoomId, parentMessageId);
    }
}
