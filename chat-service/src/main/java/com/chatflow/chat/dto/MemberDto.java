package com.chatflow.chat.dto;

import com.chatflow.chat.entity.RoomMemberEntity;
import com.chatflow.chat.entity.RoomRole;

import java.time.LocalDateTime;

public record MemberDto(
        String userId,
        String username,
        RoomRole role,
        LocalDateTime mutedUntil
) {

    public static MemberDto from(RoomMemberEntity entity) {
        return new MemberDto(
                entity.getUserId(),
                entity.getUsername(),
                entity.getRole(),
                entity.getMutedUntil()
        );
    }
}
