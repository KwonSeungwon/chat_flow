package com.gossiplog.chat.game.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** The current encounter. Deliberately carries no answer index. */
public record NextEncounter(int seq, Monster monster, Question question) {

    public record Monster(String name, String spriteKey, @JsonProperty("isBoss") boolean isBoss) {
    }

    public record Question(Long id, String stem, List<String> choices, String topicTitle) {
    }
}
