package com.gossiplog.chat.game.service;

import com.gossiplog.chat.game.dto.AnswerRequest;
import com.gossiplog.chat.game.dto.AnswerResult;
import com.gossiplog.chat.game.dto.NextEncounter;
import com.gossiplog.chat.game.dto.RunStartResponse;
import com.gossiplog.chat.game.entity.GameAnswer;
import com.gossiplog.chat.game.entity.GameMonster;
import com.gossiplog.chat.game.entity.GameProgress;
import com.gossiplog.chat.game.entity.GameProgressId;
import com.gossiplog.chat.game.entity.GameQuestion;
import com.gossiplog.chat.game.entity.GameQuestionStatus;
import com.gossiplog.chat.game.entity.GameTopic;
import com.gossiplog.chat.game.exception.GameException;
import com.gossiplog.chat.game.repository.GameAnswerRepository;
import com.gossiplog.chat.game.repository.GameDungeonRepository;
import com.gossiplog.chat.game.repository.GameMonsterRepository;
import com.gossiplog.chat.game.repository.GameProgressRepository;
import com.gossiplog.chat.game.repository.GameQuestionRepository;
import com.gossiplog.chat.game.repository.GameTopicRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Session;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Dungeon run engine: queues, encounters, HP battle, boss phase, clear and spaced repetition.
 * Also runs collection reviews (one topic, no damage taken, no progress).
 */
@Slf4j
@Service
public class GameRunService {

    static final int PLAYER_MAX_HP = 100;
    static final int NORMAL_MONSTER_HP = 30;
    static final int NORMAL_MONSTER_ATTACK = 25;
    static final int BOSS_HP = 90;
    static final int BOSS_ATTACK = 34;
    static final int PLAYER_ATTACK = 15;
    /** Streak (this answer included) from which a hit is critical (double damage). */
    static final int CRITICAL_STREAK = 3;
    static final int KILLS_TO_BOSS = 3;
    static final int NORMAL_QUEUE_SIZE = 6;
    static final int BOSS_QUEUE_SIZE = 6;
    static final int REVIEW_SIZE = 5;
    static final int MIN_APPROVED_TO_OPEN = 5;
    private static final String UNIQUE_VIOLATION = "23505";

    private final GameRunStore store;
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
        this.store = new GameRunStore(redis, objectMapper);
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
                userId, dungeonId, now(), PageRequest.of(0, NORMAL_QUEUE_SIZE));
        List<Long> normal = new ArrayList<>(due);
        fill(normal, approved, NORMAL_QUEUE_SIZE, Set.of());
        List<Long> boss = new ArrayList<>();
        fill(boss, approved, BOSS_QUEUE_SIZE, Set.copyOf(normal));
        fill(boss, approved, BOSS_QUEUE_SIZE, Set.of());

        RunState state = newState(userId, dungeonId, normal);
        state.setBossQueue(boss);
        spawnNormal(state);
        store.save(state);
        return startResponse(state);
    }

    /** Starts a review of a caught topic: up to 5 of its approved questions, due ones first. */
    @Transactional(readOnly = true)
    public RunStartResponse startReview(String userId, Long topicId) {
        GameTopic topic = topicRepository.findById(topicId).orElse(null);
        List<Long> approved = topic == null ? List.of() : questionRepository.findApprovedIdsByTopic(topicId);
        if (approved.isEmpty()) {
            throw new GameException(404, "TOPIC_NOT_FOUND", "몬스터를 찾을 수 없습니다.");
        }
        if (!answerRepository.existsCorrectOnApprovedTopic(userId, topicId)) {
            throw new GameException(409, "NOT_CAUGHT", "아직 잡지 못한 몬스터입니다.");
        }

        List<Long> queue = new ArrayList<>(answerRepository.findDueQuestionIdsByTopic(userId, topicId, now()));
        if (queue.size() > REVIEW_SIZE) queue = new ArrayList<>(queue.subList(0, REVIEW_SIZE));
        fill(queue, approved, REVIEW_SIZE, Set.of());

        RunState state = newState(userId, topic.getDungeonId(), queue);
        state.setMode(RunState.Mode.REVIEW);
        state.setTopicId(topicId);
        state.setReviewTotal(queue.size() + 1);
        state.setMonsterTopicId(topicId);
        state.setMonsterHp(PLAYER_ATTACK * state.getReviewTotal());
        state.setMonsterMaxHp(state.getMonsterHp());
        store.save(state);
        return startResponse(state);
    }

    /** A fresh run whose first question is taken off {@code queue}; the rest becomes the normal queue. */
    private static RunState newState(String userId, String dungeonId, List<Long> queue) {
        RunState state = new RunState();
        state.setRunId(UUID.randomUUID().toString());
        state.setUserId(userId);
        state.setDungeonId(dungeonId);
        state.setHp(PLAYER_MAX_HP);
        state.setHearts(hearts(PLAYER_MAX_HP));
        state.setSeq(1);
        state.setCurrentQuestionId(queue.remove(0));
        state.setNormalQueue(queue);
        return state;
    }

    private static RunStartResponse startResponse(RunState state) {
        return new RunStartResponse(state.getRunId(), state.getHearts(), 0, KILLS_TO_BOSS, state.getHp(),
                PLAYER_MAX_HP, isReview(state), state.getReviewTotal());
    }

    @Transactional(readOnly = true)
    public NextEncounter next(String userId, String runId) {
        RunState state = load(userId, runId);
        if (state.isFinished()) throw GameRunStore.runExpired();
        store.touch(runId);
        GameQuestion question = question(state.getCurrentQuestionId());
        if (question.getStatus() != GameQuestionStatus.APPROVED) {
            // rejected after it was queued: serve an approved one instead
            state.setCurrentQuestionId(takeNext(state));
            store.save(state);
            question = question(state.getCurrentQuestionId());
        }
        GameTopic topic = topicRepository.findById(question.getTopicId()).orElseThrow();
        boolean boss = state.getPhase() == RunState.Phase.BOSS;
        // the engaged monster, which may belong to another topic than this question
        GameTopic monsterTopic = boss || state.getMonsterTopicId() == null
                || state.getMonsterTopicId().equals(topic.getId())
                ? topic : topicRepository.findById(state.getMonsterTopicId()).orElse(topic);
        GameMonster monster = boss
                ? monsterRepository.findByDungeonIdAndBossTrue(state.getDungeonId()).orElse(null)
                : monsterRepository.findByTopicId(monsterTopic.getId()).orElse(null);
        NextEncounter.Monster monsterView = monster != null
                ? new NextEncounter.Monster(monster.getName(), monster.getSpriteKey(), monster.isBoss(),
                        state.getMonsterHp(), state.getMonsterMaxHp())
                : new NextEncounter.Monster(monsterTopic.getTitle(), "monster_" + state.getDungeonId(), boss,
                        state.getMonsterHp(), state.getMonsterMaxHp());
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
        if (state.isFinished()) throw GameRunStore.runExpired();
        if (request.seq() != state.getSeq()) {
            throw new GameException(409, "SEQ_MISMATCH", "현재 차례의 문제가 아닙니다.");
        }
        Integer choice = request.choiceIndex();
        if (choice == null || choice < 0 || choice > 3) {
            throw GameException.validation("choiceIndex는 0~3이어야 합니다.");
        }

        if (!store.claimSeq(runId, request.seq())) {
            AnswerResult won = load(userId, runId).getAnswered().get(request.seq());
            if (won != null) {
                return won;
            }
            throw new GameException(409, "ANSWER_IN_PROGRESS", "답안을 처리하는 중입니다.");
        }
        store.restoreOnRollback(runId, request.seq(), store.json(state));
        try {
            return applyAnswer(userId, username, runId, state, request.seq(), choice);
        } catch (RuntimeException e) {
            store.releaseSeq(runId, request.seq());
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

        boolean review = isReview(state);
        boolean boss = state.getPhase() == RunState.Phase.BOSS;
        int foughtMaxHp = state.getMonsterMaxHp();
        boolean critical = false;
        boolean defeated = false;
        boolean cleared = false;
        int damage;
        if (correct) {
            state.setStreak(state.getStreak() + 1);
            state.setBestStreak(Math.max(state.getBestStreak(), state.getStreak()));
            critical = !review && state.getStreak() >= CRITICAL_STREAK;
            damage = critical ? PLAYER_ATTACK * 2 : PLAYER_ATTACK;
            state.setMonsterHp(Math.max(0, state.getMonsterHp() - damage));
            defeated = state.getMonsterHp() == 0;
            if (review) state.setReviewCorrect(state.getReviewCorrect() + 1);
        } else {
            state.setStreak(0);
            damage = review ? 0 : boss ? BOSS_ATTACK : NORMAL_MONSTER_ATTACK;
            state.setHp(Math.max(0, state.getHp() - damage));
            state.setHearts(hearts(state.getHp()));
        }
        int foughtHp = state.getMonsterHp();
        if (review) {
            cleared = state.getSeq() >= state.getReviewTotal();
        } else if (defeated && boss) {
            cleared = true;
        } else if (defeated) {
            state.setKills(state.getKills() + 1);
            state.setMonsterTopicId(null);
            if (state.getKills() >= KILLS_TO_BOSS) {
                state.setPhase(RunState.Phase.BOSS);
                spawnBoss(state);
            }
        }
        boolean failed = !review && !cleared && state.getHp() <= 0;

        if (!review) upsertProgress(userId, username, state, cleared, now);

        AnswerResult result = new AnswerResult(correct, question.getAnswerIndex(), question.getExplanation(),
                topic.getTitle(), topic.getSourceRef(), state.getHearts(), state.getKills(),
                state.getPhase() == RunState.Phase.BOSS, cleared, failed,
                state.getHp(), PLAYER_MAX_HP, damage, critical, foughtHp, foughtMaxHp, defeated,
                review, state.getReviewCorrect(), state.getReviewTotal());

        state.getAnswered().put(seq, result);
        if (cleared || failed) {
            state.setFinished(true);
        } else {
            state.setSeq(state.getSeq() + 1);
            state.setCurrentQuestionId(takeNext(state));
            spawnNormal(state);
        }
        store.save(state);
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

    /**
     * Next unanswered, still-approved id. While a normal monster is alive, an unanswered question of
     * its topic comes first (in queue order when queued). Otherwise the phase queue, then a fresh draw.
     * Questions rejected since the run started are skipped. A review draws only from its topic.
     */
    private Long takeNext(RunState state) {
        List<Long> pool = isReview(state)
                ? questionRepository.findApprovedIdsByTopic(state.getTopicId())
                : questionRepository.findApprovedIdsByDungeon(state.getDungeonId());
        Set<Long> approved = Set.copyOf(pool);
        List<Long> queue = state.getPhase() == RunState.Phase.BOSS ? state.getBossQueue() : state.getNormalQueue();
        if (!isReview(state) && state.getPhase() == RunState.Phase.NORMAL && state.getMonsterTopicId() != null) {
            List<Long> sameTopic = questionRepository.findApprovedIdsByTopic(state.getMonsterTopicId()).stream()
                    .filter(id -> approved.contains(id) && !state.getAnsweredIds().contains(id))
                    .toList();
            if (!sameTopic.isEmpty()) {
                Long pick = queue.stream().filter(sameTopic::contains).findFirst()
                        .orElseGet(() -> sameTopic.get(random.nextInt(sameTopic.size())));
                queue.remove(pick);
                return pick;
            }
        }
        while (!queue.isEmpty()) {
            Long id = queue.remove(0);
            if (!state.getAnsweredIds().contains(id) && approved.contains(id)) return id;
        }
        return draw(state, pool);
    }

    /** In NORMAL phase with no monster engaged, a full-HP monster of the current question's topic appears. */
    private void spawnNormal(RunState state) {
        if (isReview(state) || state.getPhase() != RunState.Phase.NORMAL || state.getMonsterTopicId() != null) {
            return;
        }
        state.setMonsterTopicId(question(state.getCurrentQuestionId()).getTopicId());
        state.setMonsterHp(NORMAL_MONSTER_HP);
        state.setMonsterMaxHp(NORMAL_MONSTER_HP);
    }

    private static void spawnBoss(RunState state) {
        state.setMonsterTopicId(null);
        state.setMonsterHp(BOSS_HP);
        state.setMonsterMaxHp(BOSS_HP);
    }

    private static boolean isReview(RunState state) {
        return state.getMode() == RunState.Mode.REVIEW;
    }

    /** Hearts shown to old clients: ceil(hp * 3 / 100). */
    private static int hearts(int hp) {
        return (hp * 3 + PLAYER_MAX_HP - 1) / PLAYER_MAX_HP;
    }

    /**
     * Runs written before the HP battle have hearts but no hp and no monster: derive hp from hearts
     * (rounded up) and spawn the monster being fought.
     */
    private void upgradeLegacy(RunState state) {
        if (state.isFinished() || isReview(state)) return;
        if (state.getHp() == 0 && state.getHearts() > 0) {
            state.setHp((state.getHearts() * PLAYER_MAX_HP + 2) / 3);
        }
        if (state.getMonsterHp() == 0) {
            if (state.getPhase() == RunState.Phase.BOSS) {
                spawnBoss(state);
            } else if (state.getMonsterTopicId() == null) {
                spawnNormal(state);
            }
        }
    }

    /**
     * Random approved question not yet answered in this run. Only when every question has been
     * answered are ids reused, and even then never the question just answered (if there is another).
     */
    private Long draw(RunState state, List<Long> pool) {
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

    private RunState load(String userId, String runId) {
        RunState state = store.load(userId, runId);
        upgradeLegacy(state);
        return state;
    }
}
