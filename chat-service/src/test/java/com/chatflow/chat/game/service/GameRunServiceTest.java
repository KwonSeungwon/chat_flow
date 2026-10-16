package com.chatflow.chat.game.service;

import com.chatflow.chat.game.dto.AnswerRequest;
import com.chatflow.chat.game.dto.AnswerResult;
import com.chatflow.chat.game.dto.NextEncounter;
import com.chatflow.chat.game.dto.RunStartResponse;
import com.chatflow.chat.game.entity.GameAnswer;
import com.chatflow.chat.game.entity.GameDungeon;
import com.chatflow.chat.game.entity.GameMonster;
import com.chatflow.chat.game.entity.GameProgress;
import com.chatflow.chat.game.entity.GameProgressId;
import com.chatflow.chat.game.entity.GameQuestion;
import com.chatflow.chat.game.entity.GameQuestionStatus;
import com.chatflow.chat.game.entity.GameTopic;
import com.chatflow.chat.game.exception.GameException;
import com.chatflow.chat.game.repository.GameAnswerRepository;
import com.chatflow.chat.game.repository.GameDungeonRepository;
import com.chatflow.chat.game.repository.GameMonsterRepository;
import com.chatflow.chat.game.repository.GameProgressRepository;
import com.chatflow.chat.game.repository.GameQuestionRepository;
import com.chatflow.chat.game.repository.GameTopicRepository;
import com.chatflow.chat.repository.RepositoryTestConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DataJpaTest
@ContextConfiguration(classes = RepositoryTestConfig.class)
@ActiveProfiles("test")
class GameRunServiceTest {

    private static final String USER = "user-1";
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 16, 12, 0);

    @Autowired
    private GameDungeonRepository dungeonRepository;
    @Autowired
    private GameTopicRepository topicRepository;
    @Autowired
    private GameMonsterRepository monsterRepository;
    @Autowired
    private GameQuestionRepository questionRepository;
    @Autowired
    private GameAnswerRepository answerRepository;
    @Autowired
    private GameProgressRepository progressRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, String> store = new HashMap<>();
    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private GameRunService service;
    private GameTopic jvmTopic;
    private int hashSeq;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        lenient().when(redis.opsForValue()).thenReturn(ops);
        lenient().when(ops.get(anyString())).thenAnswer(inv -> store.get(inv.<String>getArgument(0)));
        lenient().doAnswer(inv -> store.put(inv.getArgument(0), inv.getArgument(1)))
                .when(ops).set(anyString(), anyString(), any(Duration.class));
        lenient().when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenAnswer(inv -> store.putIfAbsent(inv.getArgument(0), inv.getArgument(1)) == null);
        lenient().when(redis.delete(anyString())).thenAnswer(inv -> store.remove(inv.<String>getArgument(0)) != null);
        lenient().when(redis.expire(anyString(), any(Duration.class))).thenReturn(true);

        service = newService(progressRepository);

        dungeonRepository.save(GameDungeon.builder().id("jvm").name("JVM 대장간").sortOrder(2)
                .buildingStyle("jvm").build());
        dungeonRepository.save(GameDungeon.builder().id("db").name("DB 도서관").sortOrder(3)
                .buildingStyle("db").build());
        monsterRepository.save(GameMonster.builder().dungeonId("jvm").name("가비지 골렘")
                .spriteKey("boss_jvm").boss(true).build());
        jvmTopic = topicRepository.save(GameTopic.builder().dungeonId("jvm").title("GC")
                .sourceRef("0. Wiki/2. JVM·Spring").build());
        monsterRepository.save(GameMonster.builder().dungeonId("jvm").topicId(jvmTopic.getId())
                .name("OOM 슬라임").spriteKey("monster_jvm").boss(false).build());
    }

    // ---- start ----

    @Test
    void start_closedDungeon_returns409() {
        for (int i = 0; i < 4; i++) question(jvmTopic, GameQuestionStatus.APPROVED);
        question(jvmTopic, GameQuestionStatus.DRAFT);

        assertThatThrownBy(() -> service.start(USER, "jvm"))
                .isInstanceOfSatisfying(GameException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(409);
                    assertThat(e.getCode()).isEqualTo("DUNGEON_CLOSED");
                });
        assertThat(store).isEmpty();
    }

    @Test
    void start_unknownDungeon_returns404() {
        assertThatThrownBy(() -> service.start(USER, "nope"))
                .isInstanceOfSatisfying(GameException.class, e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    @Test
    void start_buildsQueuesWithDueWrongFirst_andDisjointBoss() {
        List<GameQuestion> qs = approved(10);
        GameQuestion dueWrong = qs.get(0);
        GameQuestion wrongThenRight = qs.get(1);
        GameQuestion wrongNotDue = qs.get(2);
        answer(USER, dueWrong, false, NOW.minusDays(2), NOW.minusDays(1));
        answer(USER, wrongThenRight, false, NOW.minusDays(3), NOW.minusDays(2));
        answer(USER, wrongThenRight, true, NOW.minusDays(2), NOW.minusDays(1));
        answer(USER, wrongNotDue, false, NOW.minusHours(1), NOW.plusHours(23));
        answer("other-user", qs.get(3), false, NOW.minusDays(2), NOW.minusDays(1));
        // a due wrong answer in another dungeon must not leak in
        GameTopic dbTopic = topicRepository.save(GameTopic.builder().dungeonId("db").title("Index").build());
        GameQuestion dbQuestion = question(dbTopic, GameQuestionStatus.APPROVED);
        answer(USER, dbQuestion, false, NOW.minusDays(2), NOW.minusDays(1));

        RunStartResponse res = service.start(USER, "jvm");

        assertThat(res.hearts()).isEqualTo(3);
        assertThat(res.kills()).isZero();
        assertThat(res.killsToBoss()).isEqualTo(5);
        RunState state = state(res.runId());
        assertThat(state.getUserId()).isEqualTo(USER);
        assertThat(state.getPhase()).isEqualTo(RunState.Phase.NORMAL);
        assertThat(state.getSeq()).isEqualTo(1);
        assertThat(state.getCurrentQuestionId()).isEqualTo(dueWrong.getId());
        List<Long> normal = new ArrayList<>();
        normal.add(state.getCurrentQuestionId());
        normal.addAll(state.getNormalQueue());
        assertThat(normal).hasSize(5).doesNotHaveDuplicates().doesNotContain(dbQuestion.getId());
        assertThat(state.getBossQueue()).hasSize(3).doesNotHaveDuplicates().doesNotContainAnyElementsOf(normal);
    }

    @Test
    void start_dueWrongCappedAtFive() {
        List<GameQuestion> qs = approved(8);
        for (int i = 0; i < 7; i++) {
            answer(USER, qs.get(i), false, NOW.minusDays(2), NOW.minusDays(1).plusMinutes(i));
        }

        RunState state = state(service.start(USER, "jvm").runId());

        List<Long> normal = new ArrayList<>();
        normal.add(state.getCurrentQuestionId());
        normal.addAll(state.getNormalQueue());
        assertThat(normal).containsExactlyElementsOf(qs.subList(0, 5).stream().map(GameQuestion::getId).toList());
    }

    @Test
    void start_fewQuestions_bossOverlapsNormal() {
        approved(5);

        RunState state = state(service.start(USER, "jvm").runId());

        assertThat(state.getNormalQueue()).hasSize(4);
        assertThat(state.getBossQueue()).hasSize(3).doesNotHaveDuplicates();
    }

    // ---- next ----

    @Test
    void next_returnsTopicMonsterAndQuestionWithoutAnswer() {
        approved(8);
        String runId = service.start(USER, "jvm").runId();

        NextEncounter next = service.next(USER, runId);

        assertThat(next.seq()).isEqualTo(1);
        assertThat(next.monster().name()).isEqualTo("OOM 슬라임");
        assertThat(next.monster().isBoss()).isFalse();
        assertThat(next.question().id()).isEqualTo(state(runId).getCurrentQuestionId());
        assertThat(next.question().choices()).containsExactly("a", "b", "c", "d");
        assertThat(next.question().topicTitle()).isEqualTo("GC");
    }

    @Test
    void otherUsersRun_isHiddenAs404() {
        approved(8);
        String runId = service.start(USER, "jvm").runId();

        assertThatThrownBy(() -> service.next("intruder", runId))
                .isInstanceOfSatisfying(GameException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(404);
                    assertThat(e.getCode()).isEqualTo("RUN_EXPIRED");
                });
        assertThatThrownBy(() -> service.answer("intruder", null, runId, new AnswerRequest(1, 0)))
                .isInstanceOfSatisfying(GameException.class, e -> assertThat(e.getCode()).isEqualTo("RUN_EXPIRED"));
        assertThatThrownBy(() -> service.next(USER, "missing"))
                .isInstanceOfSatisfying(GameException.class, e -> assertThat(e.getCode()).isEqualTo("RUN_EXPIRED"));
    }

    // ---- answers ----

    @Test
    void fiveCorrect_unlocksBoss_andBossMonsterAppears() {
        approved(8);
        String runId = service.start(USER, "jvm").runId();

        AnswerResult last = null;
        for (int i = 0; i < 5; i++) {
            last = answerCorrect(runId);
            assertThat(last.kills()).isEqualTo(i + 1);
        }

        assertThat(last.bossUnlocked()).isTrue();
        assertThat(last.cleared()).isFalse();
        assertThat(state(runId).getPhase()).isEqualTo(RunState.Phase.BOSS);
        NextEncounter boss = service.next(USER, runId);
        assertThat(boss.monster().isBoss()).isTrue();
        assertThat(boss.monster().name()).isEqualTo("가비지 골렘");
        assertThat(boss.monster().spriteKey()).isEqualTo("boss_jvm");
    }

    @Test
    void bossWrong_resetsStreak_losesHeart_andServesANewQuestion() {
        approved(8);
        String runId = service.start(USER, "jvm").runId();
        for (int i = 0; i < 5; i++) answerCorrect(runId);
        answerCorrect(runId);
        assertThat(state(runId).getBossStreak()).isEqualTo(1);

        Long missed = state(runId).getCurrentQuestionId();
        AnswerResult wrong = answerWrong(runId);

        assertThat(wrong.correct()).isFalse();
        assertThat(wrong.hearts()).isEqualTo(2);
        assertThat(wrong.failed()).isFalse();
        RunState state = state(runId);
        assertThat(state.getBossStreak()).isZero();
        assertThat(state.getBossQueue()).doesNotContain(missed);
        assertThat(state.getCurrentQuestionId()).isNotEqualTo(missed);
        assertThat(state.getAnsweredIds()).contains(missed);
    }

    @Test
    void thirdBossMiss_nextServesAFreshQuestion() {
        approved(10);
        String runId = service.start(USER, "jvm").runId();
        for (int i = 0; i < 5; i++) answerCorrect(runId);
        answerCorrect(runId);
        answerCorrect(runId);
        Long missed = state(runId).getCurrentQuestionId();
        answerWrong(runId);

        NextEncounter next = service.next(USER, runId);

        assertThat(next.monster().isBoss()).isTrue();
        assertThat(next.question().id()).isNotEqualTo(missed);
        assertThat(state(runId).getAnsweredIds()).hasSize(8).doesNotContain(next.question().id());
    }

    @Test
    void fiveQuestionDungeon_neverServesTheSameQuestionTwiceInARow() {
        approved(5);
        String runId = service.start(USER, "jvm").runId();
        // normal: 5 kills with one miss; boss: hit, miss, then three hits
        boolean[] plan = {true, false, true, true, true, true, true, false, true, true, true};
        List<Long> served = new ArrayList<>();
        AnswerResult last = null;
        for (boolean correct : plan) {
            served.add(service.next(USER, runId).question().id());
            last = correct ? answerCorrect(runId) : answerWrong(runId);
        }

        assertThat(last.cleared()).isTrue();
        for (int i = 1; i < served.size(); i++) {
            assertThat(served.get(i)).as("serve #%d", i).isNotEqualTo(served.get(i - 1));
        }
        // the first five serves cover the whole pool before anything repeats
        assertThat(Set.copyOf(served.subList(0, 5))).hasSize(5);
    }

    @Test
    void concurrentSubmit_loserGetsWinnersResult() {
        approved(8);
        String runId = service.start(USER, "jvm").runId();
        RunState before = state(runId);
        String claimKey = "chatflow:game:run:" + runId + ":seq:1";
        AnswerResult winner = new AnswerResult(true, 1, "exp", "GC", "src", 3, 1, false, false, false);
        // the winner finishes between our first read and our claim
        doAnswer(inv -> {
            RunState s = state(runId);
            s.getAnswered().put(1, winner);
            s.setSeq(2);
            store.put("chatflow:game:run:" + runId, objectMapper.writeValueAsString(s));
            return false;
        }).when(ops).setIfAbsent(eq(claimKey), anyString(), any(Duration.class));

        AnswerResult result = service.answer(USER, "앨리스", runId, new AnswerRequest(1, 0));

        assertThat(result).isEqualTo(winner);
        assertThat(answerRepository.findAll()).isEmpty();
        assertThat(before.getSeq()).isEqualTo(1);
    }

    @Test
    void concurrentSubmit_whileWinnerStillRunning_returns409() {
        approved(8);
        String runId = service.start(USER, "jvm").runId();
        store.put("chatflow:game:run:" + runId + ":seq:1", "1");

        assertThatThrownBy(() -> service.answer(USER, "앨리스", runId, new AnswerRequest(1, 0)))
                .isInstanceOfSatisfying(GameException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(409);
                    assertThat(e.getCode()).isEqualTo("ANSWER_IN_PROGRESS");
                });
        assertThat(answerRepository.findAll()).isEmpty();
        assertThat(state(runId).getSeq()).isEqualTo(1);
    }

    @Test
    void progressInsertedConcurrently_isUpdatedInstead() {
        approved(8);
        progressRepository.saveAndFlush(GameProgress.builder().userId(USER).dungeonId("jvm")
                .bestStreak(4).username("old").build());
        entityManager.clear();
        // simulate the race: our first read misses the row the other run just inserted
        GameProgressRepository racing = mock(GameProgressRepository.class, delegatesTo(progressRepository));
        doReturn(Optional.empty()).doAnswer(inv -> progressRepository.findById(inv.getArgument(0)))
                .when(racing).findById(any());
        GameRunService racingService = newService(racing);
        String runId = racingService.start(USER, "jvm").runId();

        AnswerResult result = racingService.answer(USER, "앨리스", runId, new AnswerRequest(1, 0));

        assertThat(result).isNotNull();
        entityManager.flush();
        entityManager.clear();
        GameProgress progress = progressRepository.findById(new GameProgressId(USER, "jvm")).orElseThrow();
        assertThat(progress.getUsername()).isEqualTo("앨리스");
        assertThat(progress.getBestStreak()).isEqualTo(4);
        assertThat(answerRepository.findAll()).hasSize(1);
    }

    @Test
    void bossThreeInARow_clears_upsertsProgress_andDeletesRun() {
        approved(8);
        String runId = service.start(USER, "jvm").runId();
        for (int i = 0; i < 5; i++) answerCorrect(runId);
        answerCorrect(runId);
        answerCorrect(runId);
        AnswerResult clear = answerCorrect(runId);

        assertThat(clear.cleared()).isTrue();
        assertThat(clear.failed()).isFalse();
        assertThat(store).doesNotContainKey("chatflow:game:run:" + runId);
        GameProgress progress = progressRepository.findById(new GameProgressId(USER, "jvm")).orElseThrow();
        assertThat(progress.getClearedAt()).isEqualTo(NOW);
        assertThat(progress.getBestStreak()).isEqualTo(8);
        assertThat(progress.getUsername()).isEqualTo("앨리스");
        assertThat(answerRepository.findAll()).hasSize(8).allMatch(GameAnswer::isCorrect);
    }

    @Test
    void secondClear_keepsFirstClearedAt_andMaxBestStreak() {
        approved(8);
        LocalDateTime first = NOW.minusDays(5);
        progressRepository.save(GameProgress.builder().userId(USER).dungeonId("jvm")
                .clearedAt(first).bestStreak(20).username("old").build());
        String runId = service.start(USER, "jvm").runId();
        for (int i = 0; i < 8; i++) answerCorrect(runId);

        GameProgress progress = progressRepository.findById(new GameProgressId(USER, "jvm")).orElseThrow();
        assertThat(progress.getClearedAt()).isEqualTo(first);
        assertThat(progress.getBestStreak()).isEqualTo(20);
        assertThat(progress.getUsername()).isEqualTo("앨리스");
    }

    @Test
    void bossQueueExhausted_refillsFromApprovedAndStillClears() {
        approved(8);
        String runId = service.start(USER, "jvm").runId();
        for (int i = 0; i < 5; i++) answerCorrect(runId);
        answerCorrect(runId);
        answerWrong(runId);
        answerCorrect(runId);
        answerCorrect(runId);
        RunState state = state(runId);
        assertThat(state.getBossQueue()).isEmpty();
        assertThat(state.getBossStreak()).isEqualTo(2);
        assertThat(state.getCurrentQuestionId()).isNotNull();

        AnswerResult clear = answerCorrect(runId);

        assertThat(clear.cleared()).isTrue();
        assertThat(clear.hearts()).isEqualTo(2);
    }

    @Test
    void zeroHearts_fails_andDeletesRun() {
        approved(8);
        String runId = service.start(USER, "jvm").runId();
        answerWrong(runId);
        answerWrong(runId);
        AnswerResult last = answerWrong(runId);

        assertThat(last.hearts()).isZero();
        assertThat(last.failed()).isTrue();
        assertThat(last.cleared()).isFalse();
        assertThat(store).doesNotContainKey("chatflow:game:run:" + runId);
        GameProgress progress = progressRepository.findById(new GameProgressId(USER, "jvm")).orElseThrow();
        assertThat(progress.getClearedAt()).isNull();
    }

    @Test
    void answer_recordsAnswerWithSpacedRepetitionDue() {
        approved(8);
        String runId = service.start(USER, "jvm").runId();
        Long qid = state(runId).getCurrentQuestionId();
        GameQuestion q = questionRepository.findById(qid).orElseThrow();
        answer(USER, q, true, NOW.minusDays(10), NOW.minusDays(9));

        AnswerResult result = answerCorrect(runId);

        assertThat(result.correctIndex()).isEqualTo(q.getAnswerIndex());
        assertThat(result.explanation()).isEqualTo("exp");
        assertThat(result.sourceRef()).isEqualTo("0. Wiki/2. JVM·Spring");
        GameAnswer saved = answerRepository.findAll().stream()
                .filter(a -> a.getAnsweredAt().equals(NOW)).findFirst().orElseThrow();
        assertThat(saved.isCorrect()).isTrue();
        assertThat(saved.getNextDueAt()).isEqualTo(NOW.plusDays(3));
    }

    @Test
    void sameSeqTwice_returnsStoredResult() {
        approved(8);
        String runId = service.start(USER, "jvm").runId();
        AnswerResult first = answerWrong(runId);

        AnswerResult again = service.answer(USER, "앨리스", runId, new AnswerRequest(1, 0));

        assertThat(again).isEqualTo(first);
        assertThat(state(runId).getHearts()).isEqualTo(2);
        assertThat(answerRepository.findAll()).hasSize(1);
    }

    @Test
    void wrongSeq_returns409() {
        approved(8);
        String runId = service.start(USER, "jvm").runId();

        assertThatThrownBy(() -> service.answer(USER, null, runId, new AnswerRequest(2, 0)))
                .isInstanceOfSatisfying(GameException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(409);
                    assertThat(e.getCode()).isEqualTo("SEQ_MISMATCH");
                });
    }

    @Test
    void choiceOutOfRange_returns400() {
        approved(8);
        String runId = service.start(USER, "jvm").runId();

        assertThatThrownBy(() -> service.answer(USER, null, runId, new AnswerRequest(1, 4)))
                .isInstanceOfSatisfying(GameException.class, e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    @Test
    void redisDown_returns503() {
        approved(8);
        when(ops.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        assertThatThrownBy(() -> service.next(USER, "r1"))
                .isInstanceOfSatisfying(GameException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(503);
                    assertThat(e.getCode()).isEqualTo("GAME_UNAVAILABLE");
                });
    }

    @Test
    void redisDownOnStart_returns503() {
        approved(8);
        org.mockito.Mockito.doThrow(new RedisConnectionFailureException("down"))
                .when(ops).set(anyString(), anyString(), any(Duration.class));

        assertThatThrownBy(() -> service.start(USER, "jvm"))
                .isInstanceOfSatisfying(GameException.class, e -> assertThat(e.getCode()).isEqualTo("GAME_UNAVAILABLE"));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void redisDownOnAnswer_returns503_andRollsBackTheAnswer() {
        try {
            approved(8);
            String runId = service.start(USER, "jvm").runId();
            org.mockito.Mockito.doThrow(new RedisConnectionFailureException("down"))
                    .when(ops).set(anyString(), anyString(), any(Duration.class));
            TransactionTemplate tx = new TransactionTemplate(transactionManager);

            assertThatThrownBy(() -> tx.execute(s -> service.answer(USER, "앨리스", runId, new AnswerRequest(1, 0))))
                    .isInstanceOfSatisfying(GameException.class, e -> {
                        assertThat(e.getStatus()).isEqualTo(503);
                        assertThat(e.getCode()).isEqualTo("GAME_UNAVAILABLE");
                    });
            assertThat(answerRepository.count()).isZero();
            assertThat(progressRepository.count()).isZero();
            assertThat(store).doesNotContainKey("chatflow:game:run:" + runId + ":seq:1");
        } finally {
            answerRepository.deleteAll();
            progressRepository.deleteAll();
            monsterRepository.deleteAll();
            questionRepository.deleteAll();
            topicRepository.deleteAll();
            dungeonRepository.deleteAll();
        }
    }

    // ---- helpers ----

    private GameRunService newService(GameProgressRepository progress) {
        Clock clock = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
        return new GameRunService(redis, objectMapper, dungeonRepository, topicRepository, monsterRepository,
                questionRepository, answerRepository, progress, entityManager, clock, new Random(42));
    }

    private AnswerResult answerCorrect(String runId) {
        RunState s = state(runId);
        short idx = questionRepository.findById(s.getCurrentQuestionId()).orElseThrow().getAnswerIndex();
        return service.answer(USER, "앨리스", runId, new AnswerRequest(s.getSeq(), (int) idx));
    }

    private AnswerResult answerWrong(String runId) {
        RunState s = state(runId);
        short idx = questionRepository.findById(s.getCurrentQuestionId()).orElseThrow().getAnswerIndex();
        return service.answer(USER, "앨리스", runId, new AnswerRequest(s.getSeq(), (idx + 1) % 4));
    }

    private RunState state(String runId) {
        try {
            return objectMapper.readValue(store.get("chatflow:game:run:" + runId), RunState.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<GameQuestion> approved(int n) {
        List<GameQuestion> list = new ArrayList<>();
        for (int i = 0; i < n; i++) list.add(question(jvmTopic, GameQuestionStatus.APPROVED));
        return list;
    }

    private GameQuestion question(GameTopic topic, GameQuestionStatus status) {
        hashSeq++;
        return questionRepository.save(GameQuestion.builder()
                .topicId(topic.getId())
                .stem("stem " + hashSeq)
                .choices("[\"a\",\"b\",\"c\",\"d\"]")
                .answerIndex((short) (hashSeq % 4))
                .explanation("exp")
                .status(status)
                .contentHash(String.format("%064d", hashSeq))
                .build());
    }

    private void answer(String userId, GameQuestion q, boolean correct, LocalDateTime at, LocalDateTime due) {
        answerRepository.save(GameAnswer.builder().userId(userId).questionId(q.getId()).correct(correct)
                .answeredAt(at).nextDueAt(due).build());
    }
}
