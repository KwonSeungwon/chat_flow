package com.gossiplog.chat.game.service;

import com.gossiplog.chat.game.dto.AdminQuestionDto;
import com.gossiplog.chat.game.dto.AdminQuestionPage;
import com.gossiplog.chat.game.dto.BulkUploadRequest;
import com.gossiplog.chat.game.dto.BulkUploadRequest.MonsterInput;
import com.gossiplog.chat.game.dto.BulkUploadRequest.QuestionInput;
import com.gossiplog.chat.game.dto.BulkUploadRequest.TopicInput;
import com.gossiplog.chat.game.dto.BulkUploadResult;
import com.gossiplog.chat.game.dto.QuestionPatchRequest;
import com.gossiplog.chat.game.entity.GameDungeon;
import com.gossiplog.chat.game.entity.GameMonster;
import com.gossiplog.chat.game.entity.GameQuestion;
import com.gossiplog.chat.game.entity.GameQuestionStatus;
import com.gossiplog.chat.game.entity.GameTopic;
import com.gossiplog.chat.game.exception.GameException;
import com.gossiplog.chat.game.repository.GameDungeonRepository;
import com.gossiplog.chat.game.repository.GameMonsterRepository;
import com.gossiplog.chat.game.repository.GameQuestionRepository;
import com.gossiplog.chat.game.repository.GameTopicRepository;
import com.gossiplog.chat.repository.RepositoryTestConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ContextConfiguration(classes = RepositoryTestConfig.class)
@ActiveProfiles("test")
class GameQuestionServiceTest {

    @Autowired
    private GameDungeonRepository dungeonRepository;
    @Autowired
    private GameTopicRepository topicRepository;
    @Autowired
    private GameMonsterRepository monsterRepository;
    @Autowired
    private GameQuestionRepository questionRepository;

    private GameQuestionService service;

    @BeforeEach
    void setUp() {
        for (String id : List.of("k8s", "jvm", "db", "net", "build", "ops", "ai", "shell")) {
            dungeonRepository.save(GameDungeon.builder().id(id).name(id).sortOrder(1).buildingStyle(id).build());
        }
        service = new GameQuestionService(dungeonRepository, topicRepository, monsterRepository,
                questionRepository, new ObjectMapper());
    }

    // ── hash ────────────────────────────────────────────────────

    @Test
    void contentHash_isSha256OfTrimmedStemChoicesAndAnswerIndex() throws Exception {
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                ("Q\u0001a\u0001b\u0001c\u0001d\u00012").getBytes(StandardCharsets.UTF_8)));

        assertThat(GameQuestionService.contentHash(" Q ", List.of(" a", "b ", "c", "d"), 2))
                .isEqualTo(expected)
                .hasSize(64);
    }

    // ── bulk ────────────────────────────────────────────────────

    @Test
    void bulk_insertsDraftQuestions_andCreatesTopicAndMonster() {
        BulkUploadResult result = service.bulkUpload(request(topic("jvm", "GC", null,
                question("What is GC?", 1), question("What is G1?", 2))));

        assertThat(result).isEqualTo(new BulkUploadResult(2, 0));
        GameTopic topic = topicRepository.findByDungeonIdAndTitle("jvm", "GC").orElseThrow();
        assertThat(topic.getSummary()).isEqualTo("summary");
        GameMonster monster = monsterRepository.findByTopicId(topic.getId()).orElseThrow();
        assertThat(monster.getName()).isEqualTo("OOM 슬라임");
        assertThat(monster.getSpriteKey()).isEqualTo("monster_jvm");
        assertThat(monster.isBoss()).isFalse();
        assertThat(questionRepository.findAll())
                .allSatisfy(q -> assertThat(q.getStatus()).isEqualTo(GameQuestionStatus.DRAFT));
    }

    @Test
    void bulk_skipsDuplicateHashes_acrossAndWithinBatches() {
        service.bulkUpload(request(topic("jvm", "GC", null, question("What is GC?", 1))));

        BulkUploadResult second = service.bulkUpload(request(topic("jvm", "GC", null,
                question("  What is GC?  ", 1), question("What is G1?", 0), question("What is G1?", 0))));

        assertThat(second).isEqualTo(new BulkUploadResult(1, 2));
        assertThat(questionRepository.count()).isEqualTo(2);
    }

    @Test
    void bulk_upsertsExistingTopicAndMonster() {
        service.bulkUpload(request(topic("jvm", "GC", null, question("q1", 0))));

        service.bulkUpload(new BulkUploadRequest(List.of(new TopicInput("jvm", "GC", "new summary", "0. Wiki/2",
                new MonsterInput("Heap 골렘", "monster_heap"), List.of(question("q2", 0))))));

        assertThat(topicRepository.count()).isEqualTo(1);
        GameTopic topic = topicRepository.findByDungeonIdAndTitle("jvm", "GC").orElseThrow();
        assertThat(topic.getSummary()).isEqualTo("new summary");
        assertThat(topic.getSourceRef()).isEqualTo("0. Wiki/2");
        assertThat(monsterRepository.count()).isEqualTo(1);
        GameMonster monster = monsterRepository.findByTopicId(topic.getId()).orElseThrow();
        assertThat(monster.getName()).isEqualTo("Heap 골렘");
        assertThat(monster.getSpriteKey()).isEqualTo("monster_heap");
    }

    @Test
    void bulk_rejectsWholeBatch_onAnyViolation() {
        BulkUploadRequest bad = request(
                topic("jvm", "GC", null, question("ok question", 0)),
                topic("jvm", "JIT", null, new QuestionInput("stem", List.of("a", "b", "c"), 0, "exp")));

        assertValidationError(() -> service.bulkUpload(bad));
        assertThat(questionRepository.count()).isZero();
        assertThat(topicRepository.count()).isZero();
    }

    @Test
    void bulk_validationRules() {
        assertValidationError(() -> service.bulkUpload(request(topic("nope", "GC", null, question("q", 0)))));
        assertValidationError(() -> service.bulkUpload(request(topic("jvm", " ", null, question("q", 0)))));
        assertValidationError(() -> service.bulkUpload(request(topic("jvm", "x".repeat(201), null,
                question("q", 0)))));
        assertValidationError(() -> service.bulkUpload(request(topic("jvm", "GC", null, question("q", 4)))));
        assertValidationError(() -> service.bulkUpload(request(topic("jvm", "GC", null, question("q", -1)))));
        assertValidationError(() -> service.bulkUpload(request(topic("jvm", "GC", null,
                new QuestionInput("q", List.of("a", "b", "c", "d"), null, "exp")))));
        assertValidationError(() -> service.bulkUpload(request(topic("jvm", "GC", null,
                new QuestionInput("x".repeat(501), List.of("a", "b", "c", "d"), 0, "exp")))));
        assertValidationError(() -> service.bulkUpload(request(topic("jvm", "GC", null,
                new QuestionInput("q", List.of("a", "b", "c", "d"), 0, "")))));
        assertValidationError(() -> service.bulkUpload(request(topic("jvm", "GC", null,
                new QuestionInput("q", List.of("a", "b", "c", "d"), 0, "x".repeat(1001))))));
        assertValidationError(() -> service.bulkUpload(request(topic("jvm", "GC", null,
                new QuestionInput("q", List.of("a", "b", "c", "x".repeat(201)), 0, "exp")))));
        assertValidationError(() -> service.bulkUpload(request(topic("jvm", "GC", null,
                new QuestionInput("q", List.of("a", "b", "c", " "), 0, "exp")))));
        assertValidationError(() -> service.bulkUpload(request(topic("jvm", "GC", null,
                new QuestionInput("q", List.of("a", "b", "c", "d", "e"), 0, "exp")))));
        assertValidationError(() -> service.bulkUpload(new BulkUploadRequest(null)));
    }

    // ── list ────────────────────────────────────────────────────

    @Test
    void list_returnsQuestionsOfStatusWithTopicInfo() {
        service.bulkUpload(request(topic("jvm", "GC", null, question("q1", 1), question("q2", 2))));

        AdminQuestionPage page = service.list("DRAFT", 0, 20);

        assertThat(page.total()).isEqualTo(2);
        AdminQuestionDto first = page.items().get(0);
        assertThat(first.dungeonId()).isEqualTo("jvm");
        assertThat(first.topicTitle()).isEqualTo("GC");
        assertThat(first.stem()).isEqualTo("q1");
        assertThat(first.choices()).containsExactly("a", "b", "c", "d");
        assertThat(first.answerIndex()).isEqualTo(1);
        assertThat(first.status()).isEqualTo("DRAFT");
        assertThat(service.list("APPROVED", 0, 20).total()).isZero();
    }

    @Test
    void list_rejectsUnknownStatus() {
        assertValidationError(() -> service.list("BOGUS", 0, 20));
    }

    // ── patch ───────────────────────────────────────────────────

    @Test
    void patch_approve_setsReviewedAt() {
        service.bulkUpload(request(topic("jvm", "GC", null, question("q1", 1))));
        Long id = questionRepository.findAll().get(0).getId();

        AdminQuestionDto dto = service.patch(id, new QuestionPatchRequest(null, null, null, null, "APPROVED"));

        assertThat(dto.status()).isEqualTo("APPROVED");
        GameQuestion saved = questionRepository.findById(id).orElseThrow();
        assertThat(saved.getReviewedAt()).isNotNull();
    }

    @Test
    void patch_contentChange_recomputesHash() {
        service.bulkUpload(request(topic("jvm", "GC", null, question("q1", 1))));
        GameQuestion q = questionRepository.findAll().get(0);
        String before = q.getContentHash();

        AdminQuestionDto dto = service.patch(q.getId(),
                new QuestionPatchRequest("q1 edited", null, 3, "new exp", null));

        GameQuestion saved = questionRepository.findById(q.getId()).orElseThrow();
        assertThat(dto.stem()).isEqualTo("q1 edited");
        assertThat(dto.answerIndex()).isEqualTo(3);
        assertThat(saved.getContentHash()).isNotEqualTo(before)
                .isEqualTo(GameQuestionService.contentHash("q1 edited", List.of("a", "b", "c", "d"), 3));
        assertThat(saved.getReviewedAt()).isNull();
    }

    @Test
    void patch_hashCollision_returns409() {
        service.bulkUpload(request(topic("jvm", "GC", null, question("q1", 1), question("q2", 1))));
        GameQuestion q2 = questionRepository.findAll().stream()
                .filter(q -> q.getStem().equals("q2")).findFirst().orElseThrow();

        assertThatThrownBy(() -> service.patch(q2.getId(),
                new QuestionPatchRequest("q1", null, null, null, null)))
                .isInstanceOf(GameException.class)
                .hasFieldOrPropertyWithValue("status", 409)
                .hasFieldOrPropertyWithValue("code", "DUPLICATE_QUESTION");
    }

    @Test
    void patch_invalidFields_returns400_andUnknownId_returns404() {
        service.bulkUpload(request(topic("jvm", "GC", null, question("q1", 1))));
        Long id = questionRepository.findAll().get(0).getId();

        assertValidationError(() -> service.patch(id, new QuestionPatchRequest(null, null, 4, null, null)));
        assertValidationError(() -> service.patch(id, new QuestionPatchRequest(null, null, null, null, "NOPE")));
        assertValidationError(() -> service.patch(id,
                new QuestionPatchRequest(null, List.of("a"), null, null, null)));
        assertThatThrownBy(() -> service.patch(999L, new QuestionPatchRequest(null, null, null, null, "APPROVED")))
                .isInstanceOf(GameException.class)
                .hasFieldOrPropertyWithValue("status", 404);
    }

    @Test
    void patch_contentChangeOnApproved_revertsToDraftAndClearsReviewedAt() {
        Long id = approvedQuestionId();

        AdminQuestionDto dto = service.patch(id, new QuestionPatchRequest(null, null, null, "better exp", null));

        assertThat(dto.status()).isEqualTo("DRAFT");
        assertThat(questionRepository.findById(id).orElseThrow().getReviewedAt()).isNull();
    }

    @Test
    void patch_contentChangeOnApproved_withExplicitStatus_keepsThatStatus() {
        Long id = approvedQuestionId();

        AdminQuestionDto dto = service.patch(id, new QuestionPatchRequest("q1 fixed", null, null, null, "APPROVED"));

        assertThat(dto.status()).isEqualTo("APPROVED");
        assertThat(questionRepository.findById(id).orElseThrow().getReviewedAt()).isNotNull();
    }

    @Test
    void patch_sameContentOnApproved_staysApproved() {
        Long id = approvedQuestionId();

        AdminQuestionDto dto = service.patch(id, new QuestionPatchRequest(" q1 ", null, 1, null, null));

        assertThat(dto.status()).isEqualTo("APPROVED");
    }

    @Test
    void patch_statusDraft_clearsReviewedAt() {
        Long id = approvedQuestionId();

        AdminQuestionDto dto = service.patch(id, new QuestionPatchRequest(null, null, null, null, "DRAFT"));

        assertThat(dto.status()).isEqualTo("DRAFT");
        assertThat(questionRepository.findById(id).orElseThrow().getReviewedAt()).isNull();
    }

    @Test
    void status_isCaseInsensitive() {
        service.bulkUpload(request(topic("jvm", "GC", null, question("q1", 1))));
        Long id = questionRepository.findAll().get(0).getId();

        assertThat(service.patch(id, new QuestionPatchRequest(null, null, null, null, "approved")).status())
                .isEqualTo("APPROVED");
        assertThat(service.list("Approved", 0, 20).total()).isEqualTo(1);
    }

    private Long approvedQuestionId() {
        service.bulkUpload(request(topic("jvm", "GC", null, question("q1", 1))));
        Long id = questionRepository.findAll().get(0).getId();
        service.patch(id, new QuestionPatchRequest(null, null, null, null, "APPROVED"));
        assertThat(questionRepository.findById(id).orElseThrow().getReviewedAt()).isNotNull();
        return id;
    }

    // ── helpers ─────────────────────────────────────────────────

    private static void assertValidationError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(GameException.class)
                .hasFieldOrPropertyWithValue("status", 400)
                .hasFieldOrPropertyWithValue("code", "VALIDATION_ERROR");
    }

    private static BulkUploadRequest request(TopicInput... topics) {
        return new BulkUploadRequest(List.of(topics));
    }

    private static TopicInput topic(String dungeonId, String title, String spriteKey, QuestionInput... questions) {
        return new TopicInput(dungeonId, title, "summary", "0. Wiki/2. JVM·Spring",
                new MonsterInput("OOM 슬라임", spriteKey), List.of(questions));
    }

    private static QuestionInput question(String stem, int answerIndex) {
        return new QuestionInput(stem, List.of("a", "b", "c", "d"), answerIndex, "explanation");
    }
}
