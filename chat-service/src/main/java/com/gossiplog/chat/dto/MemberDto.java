package com.gossiplog.chat.dto;

import com.gossiplog.chat.entity.RoomRole;

import java.time.LocalDateTime;

public record MemberDto(
        String userId,
        String username,
        RoomRole role,
        LocalDateTime mutedUntil
) {
}
