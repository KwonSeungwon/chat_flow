package com.chatflow.chat.dto;

import com.chatflow.chat.entity.ChatMessageEntity;

import java.time.LocalDateTime;

public record MentionItemDto(
        String messageId,
        String chatRoomId,
        String fromUsername,
        String contentPreview,
        LocalDateTime timestamp,
        boolean read
) {
    public static MentionItemDto from(ChatMessageEntity e, boolean read) {
        String c = e.getContent();
        String preview = c == null ? ""
                : (c.length() > 140 ? c.substring(0, 140) + "..." : c);
        return new MentionItemDto(
                e.getMessageId(),
                e.getChatRoomId(),
                e.getUsername(),
                preview,
                e.getTimestamp(),
                read
        );
    }
}
