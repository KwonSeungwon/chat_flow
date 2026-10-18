package com.gossiplog.chat.game.dto;

/** {@code admin}: the caller is on GAME_ADMIN_USER_IDS (the client shows the review screen). */
public record GameMeResponse(long clearedCount, int total, boolean graduated, boolean admin) {
}
