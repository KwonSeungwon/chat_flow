package com.chatflow.chat.dto;

import com.chatflow.chat.entity.ScheduledMessageEntity;

import java.time.LocalDateTime;

public record ScheduledMessageDto(
        Long id,
        String chatRoomId,
        String content,
        LocalDateTime scheduledAt,
        String status,
        LocalDateTime createdAt
) {
    public static ScheduledMessageDto from(ScheduledMessageEntity e) {
        return new ScheduledMessageDto(
                e.getId(),
                e.getChatRoomId(),
                e.getContent(),
                e.getScheduledAt(),
                e.getStatus().name(),
                e.getCreatedAt()
        );
    }
}
