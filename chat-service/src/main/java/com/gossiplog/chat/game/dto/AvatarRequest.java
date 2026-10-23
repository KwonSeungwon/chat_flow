package com.gossiplog.chat.game.dto;

/** Body of {@code PUT /api/game/me/avatar}; {@code avatar} is "boy" or "girl". */
public record AvatarRequest(String avatar) {
}
