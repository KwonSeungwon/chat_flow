package com.chatflow.chat.game.dto;

import java.util.List;

/** PATCH body; null fields are left unchanged. */
public record QuestionPatchRequest(String stem, List<String> choices, Integer answerIndex,
                                   String explanation, String status) {
}
