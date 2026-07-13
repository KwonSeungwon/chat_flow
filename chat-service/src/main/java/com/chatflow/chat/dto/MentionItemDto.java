package com.chatflow.chat.dto;

import com.chatflow.chat.entity.MessageMentionEntity;

import java.time.LocalDateTime;

public record MentionItemDto(
        String messageId,
        String chatRoomId,
        String fromUsername,
        String contentPreview,
        LocalDateTime timestamp,
        boolean read
) {
    /**
     * Factory from the structured mention row + an already-decrypted/truncated preview.
     */
    public static MentionItemDto of(MessageMentionEntity row, String preview) {
        return new MentionItemDto(
                row.getMessageId(),
                row.getRoomId(),
                row.getFromUsername(),
                preview,
                row.getCreatedAt(),
                row.isRead()
        );
    }
}
