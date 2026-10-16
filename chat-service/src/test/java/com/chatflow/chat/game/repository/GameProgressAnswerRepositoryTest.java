package com.chatflow.chat.game.repository;

import com.chatflow.chat.game.entity.GameAnswer;
import com.chatflow.chat.game.entity.GameDungeon;
import com.chatflow.chat.game.entity.GameProgress;
import com.chatflow.chat.repository.RepositoryTestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ContextConfiguration(classes = RepositoryTestConfig.class)
@ActiveProfiles("test")
class GameProgressAnswerRepositoryTest {

    @Autowired
    private GameDungeonRepository dungeonRepository;
    @Autowired
    private GameProgressRepository progressRepository;
    @Autowired
    private GameAnswerRepository answerRepository;

    @BeforeEach
    void setUp() {
        dungeonRepository.save(GameDungeon.builder().id("jvm").name("j").sortOrder(2).buildingStyle("jvm").build());
        dungeonRepository.save(GameDungeon.builder().id("db").name("d").sortOrder(3).buildingStyle("db").build());
    }

    @Test
    void countClearedGroupedByUser_countsOnlyClearedRows() {
        progress("u1", "jvm", LocalDateTime.now(), "앨리스");
        progress("u1", "db", null, "앨리스");
        progress("u2", "jvm", null, null);

        Map<String, GameProgressRepository.UserClearCount> rows = progressRepository.countClearedGroupedByUser()
                .stream().collect(Collectors.toMap(GameProgressRepository.UserClearCount::getUserId, r -> r));

        assertThat(rows).hasSize(2);
        assertThat(rows.get("u1").getClearedCount()).isEqualTo(1);
        assertThat(rows.get("u1").getUsername()).isEqualTo("앨리스");
        assertThat(rows.get("u2").getClearedCount()).isZero();
        assertThat(rows.get("u2").getUsername()).isNull();
    }

    @Test
    void countCorrectGroupedByUser_countsOnlyCorrect() {
        answer("u1", true);
        answer("u1", true);
        answer("u1", false);
        answer("u2", false);

        Map<String, Long> counts = answerRepository.countCorrectGroupedByUser().stream()
                .collect(Collectors.toMap(GameAnswerRepository.UserCorrectCount::getUserId,
                        GameAnswerRepository.UserCorrectCount::getCorrectCount));

        assertThat(counts).containsOnly(Map.entry("u1", 2L));
    }

    private void progress(String userId, String dungeonId, LocalDateTime clearedAt, String username) {
        progressRepository.save(GameProgress.builder().userId(userId).dungeonId(dungeonId)
                .clearedAt(clearedAt).username(username).bestStreak(0).build());
    }

    private void answer(String userId, boolean correct) {
        answerRepository.save(GameAnswer.builder().userId(userId).questionId(1L).correct(correct)
                .answeredAt(LocalDateTime.now()).nextDueAt(LocalDateTime.now()).build());
    }
}
