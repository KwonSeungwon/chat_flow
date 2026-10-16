package com.chatflow.chat.game.service;

import com.chatflow.chat.game.dto.BulkUploadRequest;
import com.chatflow.chat.game.dto.BulkUploadRequest.MonsterInput;
import com.chatflow.chat.game.dto.BulkUploadRequest.QuestionInput;
import com.chatflow.chat.game.dto.BulkUploadRequest.TopicInput;
import com.chatflow.chat.game.dto.QuestionPatchRequest;
import com.chatflow.chat.game.entity.GameDungeon;
import com.chatflow.chat.game.entity.GameQuestion;
import com.chatflow.chat.game.entity.GameTopic;
import com.chatflow.chat.game.exception.GameException;
import com.chatflow.chat.game.repository.GameDungeonRepository;
import com.chatflow.chat.game.repository.GameMonsterRepository;
import com.chatflow.chat.game.repository.GameQuestionRepository;
import com.chatflow.chat.game.repository.GameTopicRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** A unique-constraint race (concurrent upload or edit) surfaces as 409, not 500. */
@ExtendWith(MockitoExtension.class)
class GameQuestionServiceIntegrityTest {

    @Mock
    private GameDungeonRepository dungeonRepository;
    @Mock
    private GameTopicRepository topicRepository;
    @Mock
    private GameMonsterRepository monsterRepository;
    @Mock
    private GameQuestionRepository questionRepository;

    private GameQuestionService service;

    @BeforeEach
    void setUp() {
        service = new GameQuestionService(dungeonRepository, topicRepository, monsterRepository,
                questionRepository, new ObjectMapper());
    }

    @Test
    void bulk_concurrentDuplicateInsert_returns409() {
        when(dungeonRepository.findAll()).thenReturn(List.of(
                GameDungeon.builder().id("jvm").name("jvm").sortOrder(2).buildingStyle("jvm").build()));
        when(topicRepository.findByDungeonIdAndTitle("jvm", "GC")).thenReturn(Optional.empty());
        when(topicRepository.save(any(GameTopic.class))).thenAnswer(inv -> {
            GameTopic t = inv.getArgument(0);
            t.setId(1L);
            return t;
        });
        when(monsterRepository.findByTopicId(1L)).thenReturn(Optional.empty());
        when(questionRepository.existsByContentHash(anyString())).thenReturn(false);
        when(questionRepository.save(any(GameQuestion.class)))
                .thenThrow(new DataIntegrityViolationException("uq_game_question_content_hash"));

        BulkUploadRequest request = new BulkUploadRequest(List.of(new TopicInput("jvm", "GC", null, null,
                new MonsterInput("슬라임", null),
                List.of(new QuestionInput("q", List.of("a", "b", "c", "d"), 0, "e")))));

        assertThatThrownBy(() -> service.bulkUpload(request))
                .isInstanceOf(GameException.class)
                .hasFieldOrPropertyWithValue("status", 409)
                .hasFieldOrPropertyWithValue("code", "DUPLICATE_QUESTION");
    }

    @Test
    void patch_concurrentDuplicateHash_returns409() {
        GameQuestion question = GameQuestion.builder().id(5L).topicId(1L).stem("q")
                .choices("[\"a\",\"b\",\"c\",\"d\"]").answerIndex((short) 0).explanation("e")
                .contentHash(GameQuestionService.contentHash("q", List.of("a", "b", "c", "d"), 0)).build();
        when(questionRepository.findById(5L)).thenReturn(Optional.of(question));
        when(questionRepository.findByContentHash(anyString())).thenReturn(Optional.empty());
        when(questionRepository.saveAndFlush(any(GameQuestion.class)))
                .thenThrow(new DataIntegrityViolationException("uq_game_question_content_hash"));

        assertThatThrownBy(() -> service.patch(5L, new QuestionPatchRequest("q2", null, null, null, null)))
                .isInstanceOf(GameException.class)
                .hasFieldOrPropertyWithValue("status", 409)
                .hasFieldOrPropertyWithValue("code", "DUPLICATE_QUESTION");
    }
}
