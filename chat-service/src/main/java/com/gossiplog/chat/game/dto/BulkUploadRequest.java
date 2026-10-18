package com.gossiplog.chat.game.dto;

import java.util.List;

/** Body of POST /api/game/admin/questions/bulk — the wiki-quiz-gen skill output. */
public record BulkUploadRequest(List<TopicInput> topics) {

    public record TopicInput(String dungeonId, String title, String summary, String sourceRef,
                             MonsterInput monster, List<QuestionInput> questions) {
    }

    public record MonsterInput(String name, String spriteKey) {
    }

    public record QuestionInput(String stem, List<String> choices, Integer answerIndex, String explanation) {
    }
}
