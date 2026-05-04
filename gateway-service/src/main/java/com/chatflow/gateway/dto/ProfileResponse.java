package com.chatflow.gateway.dto;

import com.chatflow.gateway.entity.UserEntity;

public record ProfileResponse(
        String userId,
        String username,
        String role,
        String profileImageUrl,
        String statusMessage,
        String bio
) {
    public static ProfileResponse from(UserEntity user) {
        return new ProfileResponse(
                user.getUserId(),
                user.getUsername(),
                user.getRole(),
                user.getProfileImageUrl(),
                user.getStatusMessage(),
                user.getBio()
        );
    }
}
