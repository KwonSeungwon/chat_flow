package com.gossiplog.chat.game.dto;

/** Public leaderboard row. The user id is deliberately not exposed. */
public record LeaderboardEntry(String username, long clearedCount, long correctCount) {
}
