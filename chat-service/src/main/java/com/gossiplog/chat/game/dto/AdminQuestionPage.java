package com.gossiplog.chat.game.dto;

import java.util.List;

public record AdminQuestionPage(List<AdminQuestionDto> items, long total) {
}
