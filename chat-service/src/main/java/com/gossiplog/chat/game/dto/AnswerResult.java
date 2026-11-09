package com.gossiplog.chat.game.dto;

/**
 * Result of one answer. Also stored in the run state so a repeated {@code seq} returns it unchanged.
 * {@code damage} is dealt to the monster on a correct answer and taken by the player on a wrong one.
 * {@code monsterHp}/{@code monsterMaxHp} describe the monster just fought (0 when defeated), not the next one.
 * In a review run {@code cleared} means the review is finished.
 */
public record AnswerResult(boolean correct, int correctIndex, String explanation, String topicTitle,
                           String sourceRef, int hearts, int kills, boolean bossUnlocked,
                           boolean cleared, boolean failed,
                           int hp, int maxHp, int damage, boolean critical,
                           int monsterHp, int monsterMaxHp, boolean monsterDefeated,
                           boolean review, int reviewCorrect, int reviewTotal) {
}
