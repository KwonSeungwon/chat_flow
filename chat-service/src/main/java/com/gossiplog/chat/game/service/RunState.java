package com.gossiplog.chat.game.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gossiplog.chat.game.dto.AnswerResult;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One dungeon run, stored as JSON in Valkey under {@code gossiplog:game:run:{runId}}.
 * {@code currentQuestionId} has already been taken off its queue. Unknown properties are ignored
 * so runs written by an older version (e.g. {@code bossStreak}) still load.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RunState {

    public enum Phase { NORMAL, BOSS }

    /** RUN: a dungeon run. REVIEW: a collection review of one topic (no fail, no progress). */
    public enum Mode { RUN, REVIEW }

    private String runId;
    private String userId;
    private String dungeonId;
    private Mode mode = Mode.RUN;
    /** Kept for old clients: ceil(hp * 3 / 100). */
    private int hearts;
    private int hp;
    private int kills;
    private Phase phase = Phase.NORMAL;
    /** HP of the monster being fought; a new one spawns at full HP after a kill. */
    private int monsterHp;
    private int monsterMaxHp;
    /** Topic of the monster fought in NORMAL phase (and in a review); null in BOSS phase or before a spawn. */
    private Long monsterTopicId;
    /** Review only: the reviewed topic, its number of questions and the correct answers so far. */
    private Long topicId;
    private int reviewTotal;
    private int reviewCorrect;
    private int seq;
    private Long currentQuestionId;
    /** In a review run this holds the review questions. */
    private List<Long> normalQueue = new ArrayList<>();
    private List<Long> bossQueue = new ArrayList<>();
    private Map<Integer, AnswerResult> answered = new LinkedHashMap<>();
    /** Every question answered in this run; its answer was revealed, so it is not served again. */
    private Set<Long> answeredIds = new LinkedHashSet<>();
    /** Consecutive correct answers in this run (any phase); the max feeds {@code game_progress.best_streak}. */
    private int streak;
    private int bestStreak;
    /** Cleared or failed (or review finished): kept only so a resent final answer gets its stored result. */
    private boolean finished;
}
