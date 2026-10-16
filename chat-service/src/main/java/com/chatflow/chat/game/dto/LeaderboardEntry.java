package com.chatflow.chat.game.dto;

/** Public leaderboard row. The user id is deliberately not exposed. */
public record LeaderboardEntry(String username, long clearedCount, long correctCount) {
}
