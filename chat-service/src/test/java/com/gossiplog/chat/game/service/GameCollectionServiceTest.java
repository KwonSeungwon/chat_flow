package com.gossiplog.chat.game.service;

import com.gossiplog.chat.game.dto.CollectionEntry;
import com.gossiplog.chat.game.entity.GameAnswer;
import com.gossiplog.chat.game.entity.GameDungeon;
import com.gossiplog.chat.game.entity.GameMonster;
import com.gossiplog.chat.game.entity.GameQuestion;
import com.gossiplog.chat.game.entity.GameQuestionStatus;
import com.gossiplog.chat.game.entity.GameTopic;
import com.gossiplog.chat.game.repository.GameAnswerRepository;
import com.gossiplog.chat.game.repository.GameDungeonRepository;
import com.gossiplog.chat.game.repository.GameMonsterRepository;
import com.gossiplog.chat.game.repository.GameQuestionRepository;
import com.gossiplog.chat.game.repository.GameTopicRepository;
import com.gossiplog.chat.repository.RepositoryTestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ContextConfiguration(classes = RepositoryTestConfig.class)
@ActiveProfiles("test")
class GameCollectionServiceTest {

    private static final String USER = "user-1";
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 9, 12, 0);

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

    private GameCollectionService service;
    private int hashSeq;

    @BeforeEach
    void setUp() {
        service = new GameCollectionService(dungeonRepository, topicRepository, monsterRepository,
                questionRepository, answerRepository, Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE));
        dungeonRepository.save(GameDungeon.builder().id("jvm").name("JVM 대장간").sortOrder(2)
                .buildingStyle("jvm").build());
        dungeonRepository.save(GameDungeon.builder().id("k8s").name("쿠버네티스 성").sortOrder(1)
                .buildingStyle("k8s").build());
        monsterRepository.save(GameMonster.builder().dungeonId("jvm").name("가비지 골렘")
                .spriteKey("boss_jvm").boss(true).build());
    }

    @Test
    void collection_listsTopicsWithApprovedQuestions_inDungeonThenTitleOrder() {
        GameTopic gc = topic("jvm", "GC");
        GameTopic heap = topic("jvm", "Heap");
        GameTopic pod = topic("k8s", "Pod");
        GameTopic draftOnly = topic("jvm", "Draft only");
        monster(gc, "OOM 슬라임", "monster_gc");
        monster(heap, "힙 덩어리", "monster_heap");
        question(gc, GameQuestionStatus.APPROVED);
        question(heap, GameQuestionStatus.APPROVED);
        question(pod, GameQuestionStatus.APPROVED);
        question(draftOnly, GameQuestionStatus.DRAFT);

        List<CollectionEntry> list = service.collection(USER);

        assertThat(list).extracting(CollectionEntry::topicId).containsExactly(pod.getId(), gc.getId(), heap.getId());
        // 잡기 전에는 주제명도 감춘다
        assertThat(list).extracting(CollectionEntry::topicTitle).containsOnlyNulls();
        CollectionEntry pod0 = list.get(0);
        assertThat(pod0.topicId()).isEqualTo(pod.getId());
        assertThat(pod0.dungeonId()).isEqualTo("k8s");
        assertThat(pod0.dungeonName()).isEqualTo("쿠버네티스 성");
        assertThat(pod0.caught()).isFalse();
        assertThat(pod0.monsterName()).isNull();
        assertThat(pod0.spriteKey()).isEqualTo("monster_k8s");
        assertThat(list.get(1).spriteKey()).isEqualTo("monster_gc");
        assertThat(list.get(1).monsterName()).isNull();
    }

    @Test
    void collection_countsCaughtCorrectAndDue_perTopic() {
        GameTopic gc = topic("jvm", "GC");
        GameTopic heap = topic("jvm", "Heap");
        monster(gc, "OOM 슬라임", "monster_gc");
        GameQuestion g1 = question(gc, GameQuestionStatus.APPROVED);
        GameQuestion g2 = question(gc, GameQuestionStatus.APPROVED);
        GameQuestion g3 = question(gc, GameQuestionStatus.APPROVED);
        GameQuestion gRejected = question(gc, GameQuestionStatus.REJECTED);
        GameQuestion h1 = question(heap, GameQuestionStatus.APPROVED);
        // g1: right twice (counts once), latest due in the future
        answer(USER, g1, true, NOW.minusDays(5), NOW.minusDays(4));
        answer(USER, g1, true, NOW.minusDays(4), NOW.plusDays(3));
        // g2: right, then wrong and due -> correct once, due now
        answer(USER, g2, true, NOW.minusDays(5), NOW.minusDays(4));
        answer(USER, g2, false, NOW.minusDays(3), NOW.minusDays(2));
        // g3: never answered; rejected question answers never count
        answer(USER, gRejected, true, NOW.minusDays(3), NOW.minusDays(2));
        // heap: only wrong, due -> not caught, but due counts
        answer(USER, h1, false, NOW.minusDays(3), NOW.minusDays(2));
        // another user's answers never leak in
        answer("other", g3, true, NOW.minusDays(3), NOW.minusDays(2));

        List<CollectionEntry> list = service.collection(USER);

        CollectionEntry gcEntry = list.get(0);
        assertThat(gcEntry.topicTitle()).isEqualTo("GC");
        assertThat(gcEntry.caught()).isTrue();
        assertThat(gcEntry.monsterName()).isEqualTo("OOM 슬라임");
        assertThat(gcEntry.spriteKey()).isEqualTo("monster_gc");
        assertThat(gcEntry.correctCount()).isEqualTo(2);
        assertThat(gcEntry.questionCount()).isEqualTo(3);
        assertThat(gcEntry.dueCount()).isEqualTo(1);
        CollectionEntry heapEntry = list.get(1);
        assertThat(heapEntry.caught()).isFalse();
        assertThat(heapEntry.monsterName()).isNull();
        assertThat(heapEntry.correctCount()).isZero();
        assertThat(heapEntry.questionCount()).isEqualTo(1);
        assertThat(heapEntry.dueCount()).isEqualTo(1);
    }

    @Test
    void collection_caughtWithoutMonsterRow_fallsBackToTopicTitle() {
        GameTopic heap = topic("jvm", "Heap");
        GameQuestion h1 = question(heap, GameQuestionStatus.APPROVED);
        answer(USER, h1, true, NOW.minusDays(1), NOW.plusDays(1));

        CollectionEntry entry = service.collection(USER).get(0);

        assertThat(entry.caught()).isTrue();
        assertThat(entry.monsterName()).isEqualTo("Heap");
        assertThat(entry.spriteKey()).isEqualTo("monster_jvm");
    }

    @Test
    void collection_empty_whenNoApprovedQuestions() {
        topic("jvm", "GC");

        assertThat(service.collection(USER)).isEmpty();
    }

    private GameTopic topic(String dungeonId, String title) {
        return topicRepository.save(GameTopic.builder().dungeonId(dungeonId).title(title).build());
    }

    private void monster(GameTopic topic, String name, String sprite) {
        monsterRepository.save(GameMonster.builder().dungeonId(topic.getDungeonId()).topicId(topic.getId())
                .name(name).spriteKey(sprite).boss(false).build());
    }

    private GameQuestion question(GameTopic topic, GameQuestionStatus status) {
        hashSeq++;
        return questionRepository.save(GameQuestion.builder()
                .topicId(topic.getId())
                .stem("stem " + hashSeq)
                .choices("[\"a\",\"b\",\"c\",\"d\"]")
                .answerIndex((short) 0)
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
