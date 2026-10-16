package com.chatflow.chat.game.service;

import com.chatflow.chat.game.dto.AnswerRequest;
import com.chatflow.chat.game.dto.AnswerResult;
import com.chatflow.chat.game.dto.NextEncounter;
import com.chatflow.chat.game.dto.RunStartResponse;
import com.chatflow.chat.game.entity.GameAnswer;
import com.chatflow.chat.game.entity.GameMonster;
import com.chatflow.chat.game.entity.GameProgress;
import com.chatflow.chat.game.entity.GameProgressId;
import com.chatflow.chat.game.entity.GameQuestion;
import com.chatflow.chat.game.entity.GameTopic;
import com.chatflow.chat.game.exception.GameException;
import com.chatflow.chat.game.repository.GameAnswerRepository;
import com.chatflow.chat.game.repository.GameDungeonRepository;
import com.chatflow.chat.game.repository.GameMonsterRepository;
import com.chatflow.chat.game.repository.GameProgressRepository;
import com.chatflow.chat.game.repository.GameQuestionRepository;
import com.chatflow.chat.game.repository.GameTopicRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** Dungeon run engine: queues, encounters, hearts, boss phase, clear and spaced repetition. */
@Slf4j
@Service
public class GameRunService {

    static final int START_HEARTS = 3;
    static final int KILLS_TO_BOSS = 5;
    static final int BOSS_STREAK_TO_CLEAR = 3;
    static final int MIN_APPROVED_TO_OPEN = 5;
    private static final String RUN_KEY_PREFIX = "chatflow:game:run:";
    private static final Duration RUN_TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final GameDungeonRepository dungeonRepository;
    private final GameTopicRepository topicRepository;
    private final GameMonsterRepository monsterRepository;
    private final GameQuestionRepository questionRepository;
    private final GameAnswerRepository answerRepository;
    private final GameProgressRepository progressRepository;
    private final Clock clock;
    private final Random random;

    @Autowired
    public GameRunService(StringRedisTemplate redis, ObjectMapper objectMapper,
                          GameDungeonRepository dungeonRepository, GameTopicRepository topicRepository,
                          GameMonsterRepository monsterRepository, GameQuestionRepository questionRepository,
                          GameAnswerRepository answerRepository, GameProgressRepository progressRepository) {
        this(redis, objectMapper, dungeonRepository, topicRepository, monsterRepository, questionRepository,
                answerRepository, progressRepository, Clock.systemDefaultZone(), new SecureRandom());
    }

    GameRunService(StringRedisTemplate redis, ObjectMapper objectMapper,
                   GameDungeonRepository dungeonRepository, GameTopicRepository topicRepository,
                   GameMonsterRepository monsterRepository, GameQuestionRepository questionRepository,
                   GameAnswerRepository answerRepository, GameProgressRepository progressRepository,
                   Clock clock, Random random) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.dungeonRepository = dungeonRepository;
        this.topicRepository = topicRepository;
        this.monsterRepository = monsterRepository;
        this.questionRepository = questionRepository;
        this.answerRepository = answerRepository;
        this.progressRepository = progressRepository;
        this.clock = clock;
        this.random = random;
    }

    @Transactional(readOnly = true)
    public RunStartResponse start(String userId, String dungeonId) {
        if (!dungeonRepository.existsById(dungeonId)) {
            throw new GameException(404, "DUNGEON_NOT_FOUND", "건물을 찾을 수 없습니다.");
        }
        List<Long> approved = questionRepository.findApprovedIdsByDungeon(dungeonId);
        if (approved.size() < MIN_APPROVED_TO_OPEN) {
            throw new GameException(409, "DUNGEON_CLOSED", "공사 중인 건물입니다.");
        }

        List<Long> due = answerRepository.findDueWrongQuestionIds(
                userId, dungeonId, now(), PageRequest.of(0, KILLS_TO_BOSS));
        List<Long> normal = new ArrayList<>(due);
        fill(normal, approved, KILLS_TO_BOSS, Set.of());
        List<Long> boss = new ArrayList<>();
        fill(boss, approved, BOSS_STREAK_TO_CLEAR, Set.copyOf(normal));
        fill(boss, approved, BOSS_STREAK_TO_CLEAR, Set.of());

        RunState state = new RunState();
        state.setRunId(UUID.randomUUID().toString());
        state.setUserId(userId);
        state.setDungeonId(dungeonId);
        state.setHearts(START_HEARTS);
        state.setSeq(1);
        state.setCurrentQuestionId(normal.remove(0));
        state.setNormalQueue(normal);
        state.setBossQueue(boss);
        save(state);
        return new RunStartResponse(state.getRunId(), START_HEARTS, 0, KILLS_TO_BOSS);
    }

    @Transactional(readOnly = true)
    public NextEncounter next(String userId, String runId) {
        RunState state = load(userId, runId);
        touch(runId);
        GameQuestion question = question(state.getCurrentQuestionId());
        GameTopic topic = topicRepository.findById(question.getTopicId()).orElseThrow();
        GameMonster monster = state.getPhase() == RunState.Phase.BOSS
                ? monsterRepository.findByDungeonIdAndBossTrue(state.getDungeonId()).orElse(null)
                : monsterRepository.findByTopicId(topic.getId()).orElse(null);
        NextEncounter.Monster monsterView = monster != null
                ? new NextEncounter.Monster(monster.getName(), monster.getSpriteKey(), monster.isBoss())
                : new NextEncounter.Monster(topic.getTitle(), "monster_" + state.getDungeonId(),
                        state.getPhase() == RunState.Phase.BOSS);
        return new NextEncounter(state.getSeq(), monsterView,
                new NextEncounter.Question(question.getId(), question.getStem(),
                        readChoices(question.getChoices()), topic.getTitle()));
    }

    @Transactional
    public AnswerResult answer(String userId, String username, String runId, AnswerRequest request) {
        if (request == null || request.seq() == null) {
            throw GameException.validation("seq는 필수입니다.");
        }
        RunState state = load(userId, runId);
        AnswerResult previous = state.getAnswered().get(request.seq());
        if (previous != null) {
            return previous;
        }
        if (request.seq() != state.getSeq()) {
            throw new GameException(409, "SEQ_MISMATCH", "현재 차례의 문제가 아닙니다.");
        }
        Integer choice = request.choiceIndex();
        if (choice == null || choice < 0 || choice > 3) {
            throw GameException.validation("choiceIndex는 0~3이어야 합니다.");
        }

        LocalDateTime now = now();
        GameQuestion question = question(state.getCurrentQuestionId());
        GameTopic topic = topicRepository.findById(question.getTopicId()).orElseThrow();
        boolean correct = choice == question.getAnswerIndex();
        recordAnswer(userId, question.getId(), correct, now);

        boolean cleared = false;
        if (correct) {
            state.setStreak(state.getStreak() + 1);
            state.setBestStreak(Math.max(state.getBestStreak(), state.getStreak()));
            if (state.getPhase() == RunState.Phase.NORMAL) {
                state.setKills(state.getKills() + 1);
                if (state.getKills() >= KILLS_TO_BOSS) {
                    state.setPhase(RunState.Phase.BOSS);
                }
            } else {
                state.setBossStreak(state.getBossStreak() + 1);
                cleared = state.getBossStreak() >= BOSS_STREAK_TO_CLEAR;
            }
        } else {
            state.setStreak(0);
            state.setHearts(state.getHearts() - 1);
            if (state.getPhase() == RunState.Phase.BOSS) {
                state.setBossStreak(0);
                state.getBossQueue().add(question.getId());
            }
        }
        boolean failed = !cleared && state.getHearts() <= 0;

        upsertProgress(userId, username, state, cleared, now);

        AnswerResult result = new AnswerResult(correct, question.getAnswerIndex(), question.getExplanation(),
                topic.getTitle(), topic.getSourceRef(), state.getHearts(), state.getKills(),
                state.getPhase() == RunState.Phase.BOSS, cleared, failed);

        if (cleared || failed) {
            delete(runId);
        } else {
            state.getAnswered().put(request.seq(), result);
            state.setSeq(state.getSeq() + 1);
            state.setCurrentQuestionId(takeNext(state));
            save(state);
        }
        return result;
    }

    private void recordAnswer(String userId, Long questionId, boolean correct, LocalDateTime now) {
        int previousStreak = 0;
        for (GameAnswer a : answerRepository.findTop2ByUserIdAndQuestionIdOrderByIdDesc(userId, questionId)) {
            if (!a.isCorrect()) break;
            previousStreak++;
        }
        answerRepository.save(GameAnswer.builder()
                .userId(userId)
                .questionId(questionId)
                .correct(correct)
                .answeredAt(now)
                .nextDueAt(SpacedRepetition.next(previousStreak, correct, now))
                .build());
    }

    /** Creates the progress row on first answer, refreshes the display name, and records a clear. */
    private void upsertProgress(String userId, String username, RunState state, boolean cleared,
                                LocalDateTime now) {
        GameProgress progress = progressRepository.findById(new GameProgressId(userId, state.getDungeonId()))
                .orElseGet(() -> GameProgress.builder().userId(userId).dungeonId(state.getDungeonId())
                        .bestStreak(0).build());
        if (cleared) {
            if (progress.getClearedAt() == null) progress.setClearedAt(now);
            progress.setBestStreak(Math.max(progress.getBestStreak(), state.getBestStreak()));
        }
        List<GameProgress> rows = new ArrayList<>(List.of(progress));
        if (username != null && !username.isBlank()) {
            String name = username.length() > 100 ? username.substring(0, 100) : username;
            for (GameProgress other : progressRepository.findByUserId(userId)) {
                if (!other.equals(progress)) rows.add(other);
            }
            rows.forEach(p -> p.setUsername(name));
        }
        progressRepository.saveAll(rows);
    }

    private Long takeNext(RunState state) {
        List<Long> queue = state.getPhase() == RunState.Phase.BOSS ? state.getBossQueue() : state.getNormalQueue();
        if (queue.isEmpty()) {
            List<Long> approved = questionRepository.findApprovedIdsByDungeon(state.getDungeonId());
            if (approved.isEmpty()) {
                throw new GameException(409, "DUNGEON_CLOSED", "출제할 문제가 없습니다.");
            }
            return approved.get(random.nextInt(approved.size()));
        }
        return queue.remove(0);
    }

    /** Appends random ids from {@code pool} to {@code target} until it has {@code size}, skipping excluded/duplicates. */
    private void fill(List<Long> target, List<Long> pool, int size, Set<Long> exclude) {
        List<Long> shuffled = new ArrayList<>(pool);
        Collections.shuffle(shuffled, random);
        Set<Long> present = new LinkedHashSet<>(target);
        for (Long id : shuffled) {
            if (target.size() >= size) return;
            if (exclude.contains(id) || present.contains(id)) continue;
            target.add(id);
            present.add(id);
        }
    }

    private GameQuestion question(Long id) {
        return questionRepository.findById(id)
                .orElseThrow(() -> new GameException(404, "QUESTION_NOT_FOUND", "문제를 찾을 수 없습니다."));
    }

    private List<String> readChoices(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid choices JSON", e);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    // ---- Valkey ----

    private RunState load(String userId, String runId) {
        String json;
        try {
            json = redis.opsForValue().get(RUN_KEY_PREFIX + runId);
        } catch (DataAccessException e) {
            throw unavailable(e);
        }
        RunState state = null;
        if (json != null) {
            try {
                state = objectMapper.readValue(json, RunState.class);
            } catch (JsonProcessingException e) {
                log.warn("Unreadable run state {}", runId, e);
            }
        }
        if (state == null || !userId.equals(state.getUserId())) {
            throw new GameException(404, "RUN_EXPIRED", "던전이 무너졌습니다.");
        }
        return state;
    }

    private void save(RunState state) {
        String json;
        try {
            json = objectMapper.writeValueAsString(state);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize run state", e);
        }
        try {
            redis.opsForValue().set(RUN_KEY_PREFIX + state.getRunId(), json, RUN_TTL);
        } catch (DataAccessException e) {
            throw unavailable(e);
        }
    }

    private void touch(String runId) {
        try {
            redis.expire(RUN_KEY_PREFIX + runId, RUN_TTL);
        } catch (DataAccessException e) {
            throw unavailable(e);
        }
    }

    private void delete(String runId) {
        try {
            redis.delete(RUN_KEY_PREFIX + runId);
        } catch (DataAccessException e) {
            throw unavailable(e);
        }
    }

    private static GameException unavailable(DataAccessException e) {
        log.warn("Valkey unavailable for game run: {}", e.getMessage());
        return new GameException(503, "GAME_UNAVAILABLE", "게임 서버를 잠시 사용할 수 없습니다.");
    }
}
