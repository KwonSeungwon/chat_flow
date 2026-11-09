package com.gossiplog.chat.game.service;

import com.gossiplog.chat.game.dto.CollectionEntry;
import com.gossiplog.chat.game.entity.GameDungeon;
import com.gossiplog.chat.game.entity.GameMonster;
import com.gossiplog.chat.game.entity.GameTopic;
import com.gossiplog.chat.game.repository.GameAnswerRepository;
import com.gossiplog.chat.game.repository.GameDungeonRepository;
import com.gossiplog.chat.game.repository.GameMonsterRepository;
import com.gossiplog.chat.game.repository.GameQuestionRepository;
import com.gossiplog.chat.game.repository.GameTopicRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Monster collection: one entry per topic with approved questions, caught once answered correctly. */
@Service
public class GameCollectionService {

    private final GameDungeonRepository dungeonRepository;
    private final GameTopicRepository topicRepository;
    private final GameMonsterRepository monsterRepository;
    private final GameQuestionRepository questionRepository;
    private final GameAnswerRepository answerRepository;
    private final Clock clock;

    @Autowired
    public GameCollectionService(GameDungeonRepository dungeonRepository, GameTopicRepository topicRepository,
                                 GameMonsterRepository monsterRepository, GameQuestionRepository questionRepository,
                                 GameAnswerRepository answerRepository) {
        this(dungeonRepository, topicRepository, monsterRepository, questionRepository, answerRepository,
                Clock.systemDefaultZone());
    }

    GameCollectionService(GameDungeonRepository dungeonRepository, GameTopicRepository topicRepository,
                          GameMonsterRepository monsterRepository, GameQuestionRepository questionRepository,
                          GameAnswerRepository answerRepository, Clock clock) {
        this.dungeonRepository = dungeonRepository;
        this.topicRepository = topicRepository;
        this.monsterRepository = monsterRepository;
        this.questionRepository = questionRepository;
        this.answerRepository = answerRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<CollectionEntry> collection(String userId) {
        Map<Long, Long> questionCounts = questionRepository.countApprovedGroupedByTopic().stream()
                .collect(Collectors.toMap(GameQuestionRepository.TopicCount::getTopicId,
                        GameQuestionRepository.TopicCount::getQuestionCount));
        if (questionCounts.isEmpty()) return List.of();
        Map<Long, Long> correct = byTopic(answerRepository.countCorrectApprovedGroupedByTopic(userId));
        Map<Long, Long> due = byTopic(answerRepository.countDueApprovedGroupedByTopic(userId,
                LocalDateTime.now(clock)));
        Map<String, GameDungeon> dungeons = dungeonRepository.findAll().stream()
                .collect(Collectors.toMap(GameDungeon::getId, Function.identity()));
        Map<Long, GameMonster> monsters = monsterRepository.findAll().stream()
                .filter(m -> !m.isBoss() && m.getTopicId() != null)
                .collect(Collectors.toMap(GameMonster::getTopicId, Function.identity(), (a, b) -> a));

        return topicRepository.findAllById(questionCounts.keySet()).stream()
                .sorted(Comparator.comparingInt((GameTopic t) -> sortOrder(dungeons.get(t.getDungeonId())))
                        .thenComparing(GameTopic::getTitle))
                .map(t -> {
                    GameDungeon dungeon = dungeons.get(t.getDungeonId());
                    GameMonster monster = monsters.get(t.getId());
                    int correctCount = correct.getOrDefault(t.getId(), 0L).intValue();
                    boolean caught = correctCount > 0;
                    String name = !caught ? null : monster != null ? monster.getName() : t.getTitle();
                    String sprite = monster != null ? monster.getSpriteKey() : "monster_" + t.getDungeonId();
                    return new CollectionEntry(t.getId(), t.getDungeonId(),
                            dungeon != null ? dungeon.getName() : t.getDungeonId(), caught ? t.getTitle() : null,
                            name, sprite, caught, correctCount, questionCounts.get(t.getId()).intValue(),
                            due.getOrDefault(t.getId(), 0L).intValue());
                })
                .toList();
    }

    private static Map<Long, Long> byTopic(List<GameAnswerRepository.TopicCount> rows) {
        return rows.stream().collect(Collectors.toMap(GameAnswerRepository.TopicCount::getTopicId,
                GameAnswerRepository.TopicCount::getQuestionCount));
    }

    private static int sortOrder(GameDungeon dungeon) {
        return dungeon != null ? dungeon.getSortOrder() : Integer.MAX_VALUE;
    }
}
