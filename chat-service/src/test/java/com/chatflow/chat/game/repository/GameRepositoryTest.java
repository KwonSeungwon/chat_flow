package com.chatflow.chat.game.repository;

import com.chatflow.chat.game.entity.GameDungeon;
import com.chatflow.chat.game.entity.GameQuestion;
import com.chatflow.chat.game.entity.GameQuestionStatus;
import com.chatflow.chat.game.entity.GameTopic;
import com.chatflow.chat.repository.RepositoryTestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ContextConfiguration(classes = RepositoryTestConfig.class)
@ActiveProfiles("test")
class GameRepositoryTest {

    @Autowired
    private GameDungeonRepository dungeonRepository;
    @Autowired
    private GameTopicRepository topicRepository;
    @Autowired
    private GameQuestionRepository questionRepository;

    private int hashSeq;

    @BeforeEach
    void setUp() {
        dungeonRepository.save(dungeon("jvm", 2));
        dungeonRepository.save(dungeon("db", 3));
        dungeonRepository.save(dungeon("k8s", 1));
    }

    @Test
    void countApprovedByDungeon_countsOnlyApprovedPerDungeon() {
        GameTopic jvmA = topicRepository.save(topic("jvm", "GC"));
        GameTopic jvmB = topicRepository.save(topic("jvm", "JIT"));
        GameTopic db = topicRepository.save(topic("db", "Index"));

        save(jvmA, GameQuestionStatus.APPROVED);
        save(jvmA, GameQuestionStatus.APPROVED);
        save(jvmB, GameQuestionStatus.APPROVED);
        save(jvmB, GameQuestionStatus.DRAFT);
        save(db, GameQuestionStatus.APPROVED);
        save(db, GameQuestionStatus.REJECTED);

        Map<String, Long> counts = questionRepository.countApprovedByDungeon();

        assertThat(counts).containsOnly(Map.entry("jvm", 3L), Map.entry("db", 1L));
    }

    @Test
    void findApprovedIdsByDungeon_returnsOnlyApprovedOfThatDungeon() {
        GameTopic jvm = topicRepository.save(topic("jvm", "GC"));
        GameTopic db = topicRepository.save(topic("db", "Index"));
        GameQuestion approved = save(jvm, GameQuestionStatus.APPROVED);
        save(jvm, GameQuestionStatus.DRAFT);
        save(db, GameQuestionStatus.APPROVED);

        assertThat(questionRepository.findApprovedIdsByDungeon("jvm")).containsExactly(approved.getId());
        assertThat(questionRepository.findApprovedIdsByDungeon("k8s")).isEmpty();
    }

    private GameQuestion save(GameTopic topic, GameQuestionStatus status) {
        return questionRepository.save(GameQuestion.builder()
                .topicId(topic.getId())
                .stem("stem")
                .choices("[\"a\",\"b\",\"c\",\"d\"]")
                .answerIndex((short) 0)
                .explanation("exp")
                .status(status)
                .contentHash(String.format("%064d", ++hashSeq))
                .build());
    }

    private static GameDungeon dungeon(String id, int order) {
        return GameDungeon.builder().id(id).name(id).sortOrder(order).buildingStyle(id).build();
    }

    private static GameTopic topic(String dungeonId, String title) {
        return GameTopic.builder().dungeonId(dungeonId).title(title).build();
    }
}
