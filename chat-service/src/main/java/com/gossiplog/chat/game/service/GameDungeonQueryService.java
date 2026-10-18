package com.gossiplog.chat.game.service;

import com.gossiplog.chat.game.dto.DungeonSummary;
import com.gossiplog.chat.game.dto.GameMeResponse;
import com.gossiplog.chat.game.entity.GameDungeon;
import com.gossiplog.chat.game.entity.GameProgress;
import com.gossiplog.chat.game.repository.GameDungeonRepository;
import com.gossiplog.chat.game.repository.GameProgressRepository;
import com.gossiplog.chat.game.repository.GameQuestionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Village view: dungeon list with open/cleared flags, and the caller's overall progress. */
@Service
@RequiredArgsConstructor
public class GameDungeonQueryService {

    static final int TOTAL_DUNGEONS = 8;

    private final GameDungeonRepository dungeonRepository;
    private final GameQuestionRepository questionRepository;
    private final GameProgressRepository progressRepository;
    private final GameAdminGuard adminGuard;

    @Transactional(readOnly = true)
    public List<DungeonSummary> dungeons(String userId) {
        Map<String, Long> approved = questionRepository.countApprovedByDungeon();
        Map<String, Long> clears = progressRepository.countClearedGroupedByDungeon().stream()
                .collect(Collectors.toMap(GameProgressRepository.DungeonClearCount::getDungeonId,
                        GameProgressRepository.DungeonClearCount::getClearCount));
        Set<String> mine = clearedDungeonIds(userId);
        return dungeonRepository.findAll().stream()
                .sorted(Comparator.comparingInt(GameDungeon::getSortOrder))
                .map(d -> new DungeonSummary(d.getId(), d.getName(), d.getBuildingStyle(),
                        approved.getOrDefault(d.getId(), 0L) >= GameRunService.MIN_APPROVED_TO_OPEN,
                        mine.contains(d.getId()),
                        clears.getOrDefault(d.getId(), 0L)))
                .toList();
    }

    @Transactional(readOnly = true)
    public GameMeResponse me(String userId) {
        int cleared = clearedDungeonIds(userId).size();
        return new GameMeResponse(cleared, TOTAL_DUNGEONS, cleared >= TOTAL_DUNGEONS, adminGuard.isAdmin(userId));
    }

    private Set<String> clearedDungeonIds(String userId) {
        return progressRepository.findByUserId(userId).stream()
                .filter(p -> p.getClearedAt() != null)
                .map(GameProgress::getDungeonId)
                .collect(Collectors.toSet());
    }
}
