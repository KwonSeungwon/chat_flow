package com.chatflow.chat.game.dto;

public record LeaderboardEntry(String userId, String username, long clearedCount, long correctCount) {
}
