package com.chatflow.chat.game.service;

import com.chatflow.chat.game.dto.AnswerResult;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One dungeon run, stored as JSON in Valkey under {@code chatflow:game:run:{runId}}.
 * {@code currentQuestionId} has already been taken off its queue.
 */
@Data
@NoArgsConstructor
public class RunState {

    public enum Phase { NORMAL, BOSS }

    private String runId;
    private String userId;
    private String dungeonId;
    private int hearts;
    private int kills;
    private int bossStreak;
    private Phase phase = Phase.NORMAL;
    private int seq;
    private Long currentQuestionId;
    private List<Long> normalQueue = new ArrayList<>();
    private List<Long> bossQueue = new ArrayList<>();
    private Map<Integer, AnswerResult> answered = new LinkedHashMap<>();
    /** Consecutive correct answers in this run (any phase); the max feeds {@code game_progress.best_streak}. */
    private int streak;
    private int bestStreak;
}
