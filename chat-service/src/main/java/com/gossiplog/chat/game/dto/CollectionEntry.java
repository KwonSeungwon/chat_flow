package com.gossiplog.chat.game.dto;

/**
 * One topic monster in the collection. {@code monsterName} is null until caught (a correct answer on
 * one of its approved questions); {@code spriteKey} is always set so the UI can draw a silhouette.
 */
public record CollectionEntry(Long topicId, String dungeonId, String dungeonName, String topicTitle,
                              String monsterName, String spriteKey, boolean caught,
                              int correctCount, int questionCount, int dueCount) {
}
