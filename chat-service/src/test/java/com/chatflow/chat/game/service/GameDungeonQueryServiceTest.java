package com.chatflow.chat.game.service;

import com.chatflow.chat.game.dto.DungeonSummary;
import com.chatflow.chat.game.dto.GameMeResponse;
import com.chatflow.chat.game.entity.GameDungeon;
import com.chatflow.chat.game.entity.GameProgress;
import com.chatflow.chat.game.entity.GameQuestion;
import com.chatflow.chat.game.entity.GameQuestionStatus;
import com.chatflow.chat.game.entity.GameTopic;
import com.chatflow.chat.game.repository.GameDungeonRepository;
import com.chatflow.chat.game.repository.GameProgressRepository;
import com.chatflow.chat.game.repository.GameQuestionRepository;
import com.chatflow.chat.game.repository.GameTopicRepository;
import com.chatflow.chat.repository.RepositoryTestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ContextConfiguration(classes = RepositoryTestConfig.class)
@ActiveProfiles("test")
class GameDungeonQueryServiceTest {

    @Autowired
    private GameDungeonRepository dungeonRepository;
    @Autowired
    private GameTopicRepository topicRepository;
    @Autowired
    private GameQuestionRepository questionRepository;
    @Autowired
    private GameProgressRepository progressRepository;

    private GameDungeonQueryService service;
    private int hashSeq;

    @BeforeEach
    void setUp() {
        service = new GameDungeonQueryService(dungeonRepository, questionRepository, progressRepository,
                new GameAdminGuard("admin-1"));
        dungeonRepository.save(GameDungeon.builder().id("k8s").name("쿠버네티스 성").sortOrder(1).buildingStyle("k8s").build());
        dungeonRepository.save(GameDungeon.builder().id("jvm").name("JVM 대장간").sortOrder(2).buildingStyle("jvm").build());
    }

    @Test
    void dungeons_openNeedsFiveApproved_andCountsOnlyClearedRows() {
        GameTopic jvm = topicRepository.save(GameTopic.builder().dungeonId("jvm").title("GC").build());
        GameTopic k8s = topicRepository.save(GameTopic.builder().dungeonId("k8s").title("Pod").build());
        for (int i = 0; i < 5; i++) question(jvm, GameQuestionStatus.APPROVED);
        for (int i = 0; i < 4; i++) question(k8s, GameQuestionStatus.APPROVED);
        question(k8s, GameQuestionStatus.DRAFT);
        progress("me", "jvm", LocalDateTime.now());
        progress("u2", "jvm", LocalDateTime.now());
        progress("u3", "jvm", null);
        progress("me", "k8s", null);

        List<DungeonSummary> list = service.dungeons("me");

        assertThat(list).containsExactly(
                new DungeonSummary("k8s", "쿠버네티스 성", "k8s", false, false, 0),
                new DungeonSummary("jvm", "JVM 대장간", "jvm", true, true, 2));
    }

    @Test
    void me_countsClearedDungeons() {
        progress("me", "jvm", LocalDateTime.now());
        progress("me", "k8s", null);

        assertThat(service.me("me")).isEqualTo(new GameMeResponse(1, 8, false, false));
        assertThat(service.me("nobody")).isEqualTo(new GameMeResponse(0, 8, false, false));
    }

    @Test
    void me_flagsGameAdmins() {
        assertThat(service.me("admin-1").admin()).isTrue();
        assertThat(service.me("me").admin()).isFalse();
    }

    private void progress(String userId, String dungeonId, LocalDateTime clearedAt) {
        progressRepository.save(GameProgress.builder().userId(userId).dungeonId(dungeonId)
                .clearedAt(clearedAt).bestStreak(0).build());
    }

    private void question(GameTopic topic, GameQuestionStatus status) {
        hashSeq++;
        questionRepository.save(GameQuestion.builder().topicId(topic.getId()).stem("s" + hashSeq)
                .choices("[\"a\",\"b\",\"c\",\"d\"]").answerIndex((short) 0).explanation("e")
                .status(status).contentHash(String.format("%064d", hashSeq)).build());
    }
}
