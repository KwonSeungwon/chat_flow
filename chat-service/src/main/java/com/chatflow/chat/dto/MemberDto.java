package com.chatflow.chat.dto;

import com.chatflow.chat.entity.RoomRole;

import java.time.LocalDateTime;

public record MemberDto(
        String userId,
        String username,
        RoomRole role,
        LocalDateTime mutedUntil
) {
}
