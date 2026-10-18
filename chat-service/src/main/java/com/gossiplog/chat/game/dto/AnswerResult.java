package com.gossiplog.chat.game.dto;

/** Result of one answer. Also stored in the run state so a repeated {@code seq} returns it unchanged. */
public record AnswerResult(boolean correct, int correctIndex, String explanation, String topicTitle,
                           String sourceRef, int hearts, int kills, boolean bossUnlocked,
                           boolean cleared, boolean failed) {
}
