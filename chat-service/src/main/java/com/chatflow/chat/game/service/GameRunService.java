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
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Session;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
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
    private static final Duration SEQ_CLAIM_TTL = Duration.ofSeconds(10);
    private static final String UNIQUE_VIOLATION = "23505";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final GameDungeonRepository dungeonRepository;
    private final GameTopicRepository topicRepository;
    private final GameMonsterRepository monsterRepository;
    private final GameQuestionRepository questionRepository;
    private final GameAnswerRepository answerRepository;
    private final GameProgressRepository progressRepository;
    private final EntityManager entityManager;
    private final Clock clock;
    private final Random random;

    @Autowired
    public GameRunService(StringRedisTemplate redis, ObjectMapper objectMapper,
                          GameDungeonRepository dungeonRepository, GameTopicRepository topicRepository,
                          GameMonsterRepository monsterRepository, GameQuestionRepository questionRepository,
                          GameAnswerRepository answerRepository, GameProgressRepository progressRepository,
                          EntityManager entityManager) {
        this(redis, objectMapper, dungeonRepository, topicRepository, monsterRepository, questionRepository,
                answerRepository, progressRepository, entityManager, Clock.systemDefaultZone(), new SecureRandom());
    }

    GameRunService(StringRedisTemplate redis, ObjectMapper objectMapper,
                   GameDungeonRepository dungeonRepository, GameTopicRepository topicRepository,
                   GameMonsterRepository monsterRepository, GameQuestionRepository questionRepository,
                   GameAnswerRepository answerRepository, GameProgressRepository progressRepository,
                   EntityManager entityManager, Clock clock, Random random) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.dungeonRepository = dungeonRepository;
        this.topicRepository = topicRepository;
        this.monsterRepository = monsterRepository;
        this.questionRepository = questionRepository;
        this.answerRepository = answerRepository;
        this.progressRepository = progressRepository;
        this.entityManager = entityManager;
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
        if (state.isFinished()) throw runExpired();
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
        if (state.isFinished()) throw runExpired();
        if (request.seq() != state.getSeq()) {
            throw new GameException(409, "SEQ_MISMATCH", "현재 차례의 문제가 아닙니다.");
        }
        Integer choice = request.choiceIndex();
        if (choice == null || choice < 0 || choice > 3) {
            throw GameException.validation("choiceIndex는 0~3이어야 합니다.");
        }

        if (!claimSeq(runId, request.seq())) {
            AnswerResult won = load(userId, runId).getAnswered().get(request.seq());
            if (won != null) {
                return won;
            }
            throw new GameException(409, "ANSWER_IN_PROGRESS", "답안을 처리하는 중입니다.");
        }
        restoreOnRollback(runId, request.seq(), json(state));
        try {
            return applyAnswer(userId, username, runId, state, request.seq(), choice);
        } catch (RuntimeException e) {
            releaseSeq(runId, request.seq());
            throw e;
        }
    }

    private AnswerResult applyAnswer(String userId, String username, String runId, RunState state,
                                     int seq, int choice) {
        LocalDateTime now = now();
        GameQuestion question = question(state.getCurrentQuestionId());
        GameTopic topic = topicRepository.findById(question.getTopicId()).orElseThrow();
        boolean correct = choice == question.getAnswerIndex();
        recordAnswer(userId, question.getId(), correct, now);
        state.getAnsweredIds().add(question.getId());

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
                // the next boss question is a new one (takeNext never re-serves an answered id)
                state.setBossStreak(0);
            }
        }
        boolean failed = !cleared && state.getHearts() <= 0;

        upsertProgress(userId, username, state, cleared, now);

        AnswerResult result = new AnswerResult(correct, question.getAnswerIndex(), question.getExplanation(),
                topic.getTitle(), topic.getSourceRef(), state.getHearts(), state.getKills(),
                state.getPhase() == RunState.Phase.BOSS, cleared, failed);

        state.getAnswered().put(seq, result);
        if (cleared || failed) {
            state.setFinished(true);
        } else {
            state.setSeq(state.getSeq() + 1);
            state.setCurrentQuestionId(takeNext(state));
        }
        save(state);
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
        GameProgressId id = new GameProgressId(userId, state.getDungeonId());
        GameProgress progress = progressRepository.findById(id).orElse(null);
        if (progress == null) {
            insertProgressIfAbsent(userId, state.getDungeonId());
            progress = progressRepository.findById(id).orElseThrow();
        }
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

    /**
     * Inserts an empty progress row under a savepoint. Two concurrent first answers by the same user
     * (two runs) race on the PK; the loser's unique violation is rolled back to the savepoint, so the
     * surrounding transaction stays usable (Postgres would otherwise abort it) and the row the winner
     * inserted is updated instead.
     */
    private void insertProgressIfAbsent(String userId, String dungeonId) {
        entityManager.unwrap(Session.class).doWork(conn -> {
            Savepoint savepoint = conn.setSavepoint();
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO game_progress (user_id, dungeon_id, best_streak) VALUES (?, ?, 0)")) {
                ps.setString(1, userId);
                ps.setString(2, dungeonId);
                ps.executeUpdate();
                conn.releaseSavepoint(savepoint);
            } catch (SQLException e) {
                if (!UNIQUE_VIOLATION.equals(e.getSQLState())) throw e;
                conn.rollback(savepoint);
                log.info("game_progress ({}, {}) inserted concurrently; updating it instead", userId, dungeonId);
            }
        });
    }

    /** Next unanswered id from the phase queue, or a fresh draw when the queue has none left. */
    private Long takeNext(RunState state) {
        List<Long> queue = state.getPhase() == RunState.Phase.BOSS ? state.getBossQueue() : state.getNormalQueue();
        while (!queue.isEmpty()) {
            Long id = queue.remove(0);
            if (!state.getAnsweredIds().contains(id)) return id;
        }
        return draw(state);
    }

    /**
     * Random approved question not yet answered in this run. Only when every question has been
     * answered are ids reused, and even then never the question just answered (if there is another).
     */
    private Long draw(RunState state) {
        List<Long> pool = questionRepository.findApprovedIdsByDungeon(state.getDungeonId());
        List<Long> candidates = pool.stream().filter(id -> !state.getAnsweredIds().contains(id)).toList();
        if (candidates.isEmpty()) {
            candidates = pool.size() >= 2
                    ? pool.stream().filter(id -> !id.equals(state.getCurrentQuestionId())).toList()
                    : pool;
        }
        if (candidates.isEmpty()) {
            throw new GameException(409, "DUNGEON_CLOSED", "출제할 문제가 없습니다.");
        }
        return candidates.get(random.nextInt(candidates.size()));
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
            throw runExpired();
        }
        return state;
    }

    private static GameException runExpired() {
        return new GameException(404, "RUN_EXPIRED", "던전이 무너졌습니다.");
    }

    private String json(RunState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize run state", e);
        }
    }

    private void save(RunState state) {
        String json = json(state);
        try {
            redis.opsForValue().set(RUN_KEY_PREFIX + state.getRunId(), json, RUN_TTL);
        } catch (DataAccessException e) {
            throw unavailable(e);
        }
    }

    /**
     * The run state is written inside the answer transaction (a Valkey outage then rolls the
     * answer back). If the transaction itself rolls back afterwards, put the state and the seq
     * claim back so Valkey never runs ahead of the database.
     */
    private void restoreOnRollback(String runId, int seq, String previousJson) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_ROLLED_BACK) return;
                try {
                    redis.opsForValue().set(RUN_KEY_PREFIX + runId, previousJson, RUN_TTL);
                } catch (DataAccessException e) {
                    log.warn("Could not restore run state {} after rollback: {}", runId, e.getMessage());
                }
                releaseSeq(runId, seq);
            }
        });
    }

    private void touch(String runId) {
        try {
            redis.expire(RUN_KEY_PREFIX + runId, RUN_TTL);
        } catch (DataAccessException e) {
            throw unavailable(e);
        }
    }

    /** {@code SET ...:seq:{seq} NX EX 10}; false if another request already holds this seq. */
    private boolean claimSeq(String runId, int seq) {
        try {
            return Boolean.TRUE.equals(redis.opsForValue()
                    .setIfAbsent(seqClaimKey(runId, seq), "1", SEQ_CLAIM_TTL));
        } catch (DataAccessException e) {
            throw unavailable(e);
        }
    }

    private void releaseSeq(String runId, int seq) {
        try {
            redis.delete(seqClaimKey(runId, seq));
        } catch (DataAccessException e) {
            log.warn("Could not release seq claim {}:{} (expires in {}s)", runId, seq, SEQ_CLAIM_TTL.toSeconds());
        }
    }

    private static String seqClaimKey(String runId, int seq) {
        return RUN_KEY_PREFIX + runId + ":seq:" + seq;
    }

    private static GameException unavailable(DataAccessException e) {
        log.warn("Valkey unavailable for game run: {}", e.getMessage());
        return new GameException(503, "GAME_UNAVAILABLE", "게임 서버를 잠시 사용할 수 없습니다.");
    }
}
