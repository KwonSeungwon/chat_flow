package com.gossiplog.chat.game.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** The current encounter. Deliberately carries no answer index. */
public record NextEncounter(int seq, Monster monster, Question question) {

    /** {@code hp}: the monster's HP before this answer. */
    public record Monster(String name, String spriteKey, @JsonProperty("isBoss") boolean isBoss, int hp, int maxHp) {
    }

    public record Question(Long id, String stem, List<String> choices, String topicTitle) {
    }
}
