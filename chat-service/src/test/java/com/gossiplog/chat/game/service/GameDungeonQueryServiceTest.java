package com.gossiplog.chat.game.service;

import com.gossiplog.chat.game.dto.DungeonSummary;
import com.gossiplog.chat.game.dto.GameMeResponse;
import com.gossiplog.chat.game.entity.GameDungeon;
import com.gossiplog.chat.game.entity.GameProgress;
import com.gossiplog.chat.game.entity.GameQuestion;
import com.gossiplog.chat.game.entity.GameQuestionStatus;
import com.gossiplog.chat.game.entity.GameTopic;
import com.gossiplog.chat.game.repository.GameDungeonRepository;
import com.gossiplog.chat.game.repository.GamePlayerRepository;
import com.gossiplog.chat.game.repository.GameProgressRepository;
import com.gossiplog.chat.game.repository.GameQuestionRepository;
import com.gossiplog.chat.game.repository.GameTopicRepository;
import com.gossiplog.chat.repository.RepositoryTestConfig;
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

    @Autowired
    private GamePlayerRepository playerRepository;

    private GameDungeonQueryService service;
    private GamePlayerService playerService;
    private int hashSeq;

    @BeforeEach
    void setUp() {
        service = new GameDungeonQueryService(dungeonRepository, questionRepository, progressRepository,
                playerRepository, new GameAdminGuard("admin-1"));
        playerService = new GamePlayerService(playerRepository);
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

        assertThat(service.me("me")).isEqualTo(new GameMeResponse(1, 8, false, false, null));
        assertThat(service.me("nobody")).isEqualTo(new GameMeResponse(0, 8, false, false, null));
    }

    @Test
    void me_flagsGameAdmins() {
        assertThat(service.me("admin-1").admin()).isTrue();
        assertThat(service.me("me").admin()).isFalse();
    }

    @Test
    void avatar_nullUntilSet_thenUpsertedPerUser() {
        assertThat(service.me("me").avatar()).isNull();

        playerService.setAvatar("me", "boy");
        assertThat(service.me("me").avatar()).isEqualTo("boy");
        assertThat(service.me("other").avatar()).isNull();

        playerService.setAvatar("me", "girl");
        assertThat(service.me("me").avatar()).isEqualTo("girl");
        assertThat(playerRepository.count()).isEqualTo(1);
    }

    @Test
    void setAvatar_rejectsUnknownValue() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> playerService.setAvatar("me", "cat"))
                .isInstanceOf(com.gossiplog.chat.game.exception.GameException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> playerService.setAvatar("me", null))
                .isInstanceOf(com.gossiplog.chat.game.exception.GameException.class);
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
