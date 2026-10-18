package com.gossiplog.gateway.dto;

import com.gossiplog.gateway.entity.UserEntity;

public record ProfileResponse(
        String userId,
        String username,
        String profileImageUrl,
        String statusMessage,
        String bio
) {
    public static ProfileResponse from(UserEntity user) {
        return new ProfileResponse(
                user.getUserId(),
                user.getUsername(),
                user.getProfileImageUrl(),
                user.getStatusMessage(),
                user.getBio()
        );
    }
}
