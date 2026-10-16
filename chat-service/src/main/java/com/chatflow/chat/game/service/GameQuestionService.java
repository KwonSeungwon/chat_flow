package com.chatflow.chat.game.service;

import com.chatflow.chat.game.dto.AdminQuestionDto;
import com.chatflow.chat.game.dto.AdminQuestionPage;
import com.chatflow.chat.game.dto.BulkUploadRequest;
import com.chatflow.chat.game.dto.BulkUploadRequest.MonsterInput;
import com.chatflow.chat.game.dto.BulkUploadRequest.QuestionInput;
import com.chatflow.chat.game.dto.BulkUploadRequest.TopicInput;
import com.chatflow.chat.game.dto.BulkUploadResult;
import com.chatflow.chat.game.dto.QuestionPatchRequest;
import com.chatflow.chat.game.entity.GameDungeon;
import com.chatflow.chat.game.entity.GameMonster;
import com.chatflow.chat.game.entity.GameQuestion;
import com.chatflow.chat.game.entity.GameQuestionStatus;
import com.chatflow.chat.game.entity.GameTopic;
import com.chatflow.chat.game.exception.GameException;
import com.chatflow.chat.game.repository.GameDungeonRepository;
import com.chatflow.chat.game.repository.GameMonsterRepository;
import com.chatflow.chat.game.repository.GameQuestionRepository;
import com.chatflow.chat.game.repository.GameTopicRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Question bank: bulk upload from the wiki-quiz-gen skill and admin review. */
@Slf4j
@Service
@RequiredArgsConstructor
public class GameQuestionService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final String SEP = "\u0001";

    private final GameDungeonRepository dungeonRepository;
    private final GameTopicRepository topicRepository;
    private final GameMonsterRepository monsterRepository;
    private final GameQuestionRepository questionRepository;
    private final ObjectMapper objectMapper;

    /** SHA-256 hex of {@code stem.trim() ␁ choice.trim()… ␁ answerIndex}. */
    public static String contentHash(String stem, List<String> choices, int answerIndex) {
        String normalized = stem.trim() + SEP
                + choices.stream().map(String::trim).collect(Collectors.joining(SEP)) + SEP + answerIndex;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Validates the whole batch first; any violation rejects everything with 400. */
    @Transactional
    public BulkUploadResult bulkUpload(BulkUploadRequest request) {
        Set<String> dungeonIds = dungeonRepository.findAll().stream()
                .map(GameDungeon::getId).collect(Collectors.toSet());
        validateBatch(request, dungeonIds);

        try {
            return insertBatch(request);
        } catch (DataIntegrityViolationException e) {
            // A concurrent upload inserted the same hash (or topic) first; the transaction rolls back.
            throw duplicate("동시에 올라온 같은 내용의 문제가 있습니다. 다시 시도하세요.");
        }
    }

    private BulkUploadResult insertBatch(BulkUploadRequest request) {
        int inserted = 0;
        int skipped = 0;
        Set<String> seenHashes = new HashSet<>();
        for (TopicInput input : request.topics()) {
            GameTopic topic = upsertTopic(input);
            upsertMonster(topic, input.monster());
            for (QuestionInput q : input.questions()) {
                List<String> choices = q.choices().stream().map(String::trim).toList();
                String hash = contentHash(q.stem(), choices, q.answerIndex());
                if (!seenHashes.add(hash) || questionRepository.existsByContentHash(hash)) {
                    skipped++;
                    continue;
                }
                questionRepository.save(GameQuestion.builder()
                        .topicId(topic.getId())
                        .stem(q.stem().trim())
                        .choices(writeChoices(choices))
                        .answerIndex(q.answerIndex().shortValue())
                        .explanation(q.explanation().trim())
                        .status(GameQuestionStatus.DRAFT)
                        .contentHash(hash)
                        .build());
                inserted++;
            }
        }
        log.info("Game question bulk upload: inserted={}, skipped={}", inserted, skipped);
        return new BulkUploadResult(inserted, skipped);
    }

    @Transactional(readOnly = true)
    public AdminQuestionPage list(String status, int page, int size) {
        GameQuestionStatus parsed = parseStatus(status);
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw GameException.validation("page는 0 이상, size는 1~" + MAX_PAGE_SIZE + "이어야 합니다.");
        }
        Page<GameQuestion> result = questionRepository.findByStatusOrderByIdAsc(parsed, PageRequest.of(page, size));
        Map<Long, GameTopic> topics = topicRepository.findAllById(
                        result.getContent().stream().map(GameQuestion::getTopicId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(GameTopic::getId, Function.identity()));
        List<AdminQuestionDto> items = result.getContent().stream()
                .map(q -> toDto(q, topics.get(q.getTopicId())))
                .toList();
        return new AdminQuestionPage(items, result.getTotalElements());
    }

    @Transactional
    public AdminQuestionDto patch(Long id, QuestionPatchRequest request) {
        GameQuestion question = questionRepository.findById(id)
                .orElseThrow(() -> new GameException(404, "QUESTION_NOT_FOUND", "문제를 찾을 수 없습니다."));

        if (request.stem() != null) {
            requireLength(request.stem(), 500, "stem");
        }
        if (request.choices() != null) {
            validateChoices(request.choices());
        }
        if (request.answerIndex() != null) {
            validateAnswerIndex(request.answerIndex());
        }
        if (request.explanation() != null) {
            requireLength(request.explanation(), 1000, "explanation");
        }
        GameQuestionStatus newStatus = request.status() == null ? null : parseStatus(request.status());

        String stem = request.stem() != null ? request.stem().trim() : question.getStem();
        List<String> oldChoices = readChoices(question.getChoices());
        List<String> choices = request.choices() != null
                ? request.choices().stream().map(String::trim).toList()
                : oldChoices;
        int answerIndex = request.answerIndex() != null ? request.answerIndex() : question.getAnswerIndex();
        String explanation = request.explanation() != null ? request.explanation().trim() : question.getExplanation();
        boolean contentChanged = !stem.equals(question.getStem()) || !choices.equals(oldChoices)
                || answerIndex != question.getAnswerIndex() || !explanation.equals(question.getExplanation());

        String hash = contentHash(stem, choices, answerIndex);
        if (!hash.equals(question.getContentHash())) {
            questionRepository.findByContentHash(hash)
                    .filter(other -> !other.getId().equals(question.getId()))
                    .ifPresent(other -> {
                        throw duplicate("같은 내용의 문제가 이미 있습니다. (id=" + other.getId() + ")");
                    });
            question.setContentHash(hash);
        }
        question.setStem(stem);
        question.setChoices(writeChoices(choices));
        question.setAnswerIndex((short) answerIndex);
        question.setExplanation(explanation);

        // Editing an approved question sends it back to review unless the PATCH sets status itself.
        if (newStatus == null && contentChanged && question.getStatus() == GameQuestionStatus.APPROVED) {
            newStatus = GameQuestionStatus.DRAFT;
        }
        if (newStatus == GameQuestionStatus.DRAFT) {
            question.setStatus(GameQuestionStatus.DRAFT);
            question.setReviewedAt(null);
        } else if (newStatus != null && newStatus != question.getStatus()) {
            question.setStatus(newStatus);
            question.setReviewedAt(LocalDateTime.now());
        }
        GameQuestion saved;
        try {
            saved = questionRepository.saveAndFlush(question);
        } catch (DataIntegrityViolationException e) {
            throw duplicate("같은 내용의 문제가 이미 있습니다.");
        }
        GameTopic topic = topicRepository.findById(saved.getTopicId()).orElse(null);
        return toDto(saved, topic);
    }

    // ── upsert ──────────────────────────────────────────────────

    private GameTopic upsertTopic(TopicInput input) {
        String title = input.title().trim();
        GameTopic topic = topicRepository.findByDungeonIdAndTitle(input.dungeonId(), title)
                .orElseGet(() -> GameTopic.builder().dungeonId(input.dungeonId()).title(title).build());
        topic.setSummary(input.summary());
        topic.setSourceRef(input.sourceRef());
        return topicRepository.save(topic);
    }

    private void upsertMonster(GameTopic topic, MonsterInput input) {
        String spriteKey = isBlank(input.spriteKey()) ? "monster_" + topic.getDungeonId() : input.spriteKey().trim();
        GameMonster monster = monsterRepository.findByTopicId(topic.getId())
                .orElseGet(() -> GameMonster.builder()
                        .topicId(topic.getId()).dungeonId(topic.getDungeonId()).boss(false).build());
        monster.setName(input.name().trim());
        monster.setSpriteKey(spriteKey);
        monsterRepository.save(monster);
    }

    // ── validation ──────────────────────────────────────────────

    private void validateBatch(BulkUploadRequest request, Set<String> dungeonIds) {
        if (request == null || request.topics() == null || request.topics().isEmpty()) {
            throw GameException.validation("topics가 비어 있습니다.");
        }
        for (TopicInput topic : request.topics()) {
            if (topic == null || topic.dungeonId() == null || !dungeonIds.contains(topic.dungeonId())) {
                throw GameException.validation("알 수 없는 dungeonId입니다.");
            }
            requireLength(topic.title(), 200, "title");
            if (topic.sourceRef() != null && topic.sourceRef().length() > 500) {
                throw GameException.validation("sourceRef는 500자 이하여야 합니다.");
            }
            if (topic.monster() == null) {
                throw GameException.validation("monster가 필요합니다.");
            }
            requireLength(topic.monster().name(), 100, "monster.name");
            if (topic.monster().spriteKey() != null && topic.monster().spriteKey().trim().length() > 100) {
                throw GameException.validation("monster.spriteKey는 100자 이하여야 합니다.");
            }
            if (topic.questions() == null || topic.questions().isEmpty()) {
                throw GameException.validation("questions가 비어 있습니다.");
            }
            for (QuestionInput q : topic.questions()) {
                if (q == null) {
                    throw GameException.validation("question이 비어 있습니다.");
                }
                requireLength(q.stem(), 500, "stem");
                validateChoices(q.choices());
                validateAnswerIndex(q.answerIndex());
                requireLength(q.explanation(), 1000, "explanation");
            }
        }
    }

    private static void validateChoices(List<String> choices) {
        if (choices == null || choices.size() != 4) {
            throw GameException.validation("choices는 정확히 4개여야 합니다.");
        }
        for (String choice : choices) {
            requireLength(choice, 200, "choice");
        }
    }

    private static void validateAnswerIndex(Integer answerIndex) {
        if (answerIndex == null || answerIndex < 0 || answerIndex > 3) {
            throw GameException.validation("answerIndex는 0~3이어야 합니다.");
        }
    }

    private static void requireLength(String value, int max, String field) {
        if (value == null || value.trim().isEmpty() || value.trim().length() > max) {
            throw GameException.validation(field + "는 1~" + max + "자여야 합니다.");
        }
    }

    private static GameQuestionStatus parseStatus(String status) {
        try {
            return GameQuestionStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw GameException.validation("status는 DRAFT, APPROVED, REJECTED 중 하나여야 합니다.");
        }
    }

    private static GameException duplicate(String message) {
        return new GameException(409, "DUPLICATE_QUESTION", message);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    // ── mapping ─────────────────────────────────────────────────

    private AdminQuestionDto toDto(GameQuestion q, GameTopic topic) {
        return new AdminQuestionDto(q.getId(),
                topic == null ? null : topic.getDungeonId(),
                topic == null ? null : topic.getTitle(),
                q.getStem(), readChoices(q.getChoices()), q.getAnswerIndex(), q.getExplanation(),
                q.getStatus().name());
    }

    private String writeChoices(List<String> choices) {
        try {
            return objectMapper.writeValueAsString(choices);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("choices 직렬화 실패", e);
        }
    }

    private List<String> readChoices(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("choices 역직렬화 실패", e);
        }
    }
}
