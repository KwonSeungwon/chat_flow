package com.chatflow.chat.game.service;

import com.chatflow.chat.game.dto.LeaderboardEntry;
import com.chatflow.chat.game.repository.GameAnswerRepository;
import com.chatflow.chat.game.repository.GameProgressRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Top 20 by cleared dungeons, then correct answers. Cached in Valkey for 60 seconds.
 * The cache is only an optimization: if Valkey is down the ranking is computed from the DB.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeaderboardService {

    private static final String CACHE_KEY = "chatflow:game:leaderboard";
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);
    private static final int LIMIT = 20;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final GameProgressRepository progressRepository;
    private final GameAnswerRepository answerRepository;

    @Transactional(readOnly = true)
    public List<LeaderboardEntry> top() {
        List<LeaderboardEntry> cached = readCache();
        if (cached != null) {
            return cached;
        }
        List<LeaderboardEntry> computed = compute();
        writeCache(computed);
        return computed;
    }

    private List<LeaderboardEntry> compute() {
        Map<String, Long> correct = answerRepository.countCorrectGroupedByUser().stream()
                .collect(Collectors.toMap(GameAnswerRepository.UserCorrectCount::getUserId,
                        GameAnswerRepository.UserCorrectCount::getCorrectCount));
        record Ranked(String userId, LeaderboardEntry entry) {
        }
        return progressRepository.countClearedGroupedByUser().stream()
                .map(r -> new Ranked(r.getUserId(), new LeaderboardEntry(r.getUsername(), r.getClearedCount(),
                        correct.getOrDefault(r.getUserId(), 0L))))
                .sorted(Comparator.comparingLong((Ranked r) -> r.entry().clearedCount()).reversed()
                        .thenComparing(Comparator.comparingLong((Ranked r) -> r.entry().correctCount()).reversed())
                        .thenComparing(Ranked::userId))
                .limit(LIMIT)
                .map(Ranked::entry)
                .toList();
    }

    private List<LeaderboardEntry> readCache() {
        try {
            String json = redis.opsForValue().get(CACHE_KEY);
            return json == null ? null : objectMapper.readValue(json, new TypeReference<List<LeaderboardEntry>>() { });
        } catch (DataAccessException | JsonProcessingException e) {
            log.warn("Leaderboard cache read failed: {}", e.getMessage());
            return null;
        }
    }

    private void writeCache(List<LeaderboardEntry> entries) {
        try {
            redis.opsForValue().set(CACHE_KEY, objectMapper.writeValueAsString(entries), CACHE_TTL);
        } catch (DataAccessException | JsonProcessingException e) {
            log.warn("Leaderboard cache write failed: {}", e.getMessage());
        }
    }
}
