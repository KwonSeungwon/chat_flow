package com.gossiplog.chat.service.message;

import com.gossiplog.chat.entity.ChatMessageEntity;
import com.gossiplog.chat.repository.ChatMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Read-only service for fetching the reply chain of a parent message.
 * The controller maps the entities to {@code ChatMessageResponse} (the same
 * DTO as the GET /messages history endpoints), which carries reactions,
 * edited flag, editedAt, and pinned — fields the lean ChatMessage DTO omits.
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
