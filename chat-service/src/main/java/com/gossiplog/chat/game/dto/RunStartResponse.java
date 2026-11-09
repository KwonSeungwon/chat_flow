package com.gossiplog.chat.game.dto;

/** {@code review}/{@code reviewTotal}: a collection review run and its number of questions (0 for a normal run). */
public record RunStartResponse(String runId, int hearts, int kills, int killsToBoss, int hp, int maxHp,
                               boolean review, int reviewTotal) {
}
