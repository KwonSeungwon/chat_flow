package com.chatflow.chat.dto;

import com.chatflow.chat.entity.RoomBanEntity;

import java.time.LocalDateTime;

public record BanDto(
        String userId,
        String username,
        String bannedBy,
        String reason,
        LocalDateTime bannedAt
) {

    /**
     * Builds a BanDto from a ban entity.
     *
     * @param entity       the ban entity
     * @param bannedByUsername the username of the user who performed the ban (may be null)
     */
    public static BanDto from(RoomBanEntity entity, String bannedByUsername) {
        return new BanDto(
                entity.getUserId(),
                null, // username of banned user is not stored in ban entity (they were removed from room)
                bannedByUsername != null ? bannedByUsername : "(unknown)",
                entity.getReason(),
                entity.getBannedAt()
        );
    }
}
