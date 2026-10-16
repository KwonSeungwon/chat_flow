package com.chatflow.chat.game.dto;

import java.util.List;

/** Admin review view of a question. Includes answerIndex — admin API only. */
public record AdminQuestionDto(Long id, String dungeonId, String topicTitle, String stem,
                               List<String> choices, int answerIndex, String explanation, String status) {
}
