package com.chatflow.chat.dto;

import java.time.LocalDateTime;

public record ScheduledMessageDto(
        Long id,
        String chatRoomId,
        String content,
        LocalDateTime scheduledAt,
        String status,
        LocalDateTime createdAt
) {
}
