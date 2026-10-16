package com.chatflow.chat.game.service;

import com.chatflow.chat.game.dto.LeaderboardEntry;
import com.chatflow.chat.game.repository.GameAnswerRepository;
import com.chatflow.chat.game.repository.GameProgressRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LeaderboardServiceTest {

    private static final String KEY = "chatflow:game:leaderboard";

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> ops;
    @Mock
    private GameProgressRepository progressRepository;
    @Mock
    private GameAnswerRepository answerRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private LeaderboardService service;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(ops);
        service = new LeaderboardService(redis, objectMapper, progressRepository, answerRepository);
    }

    @Test
    void cacheHit_returnsCachedWithoutQueryingDb() throws Exception {
        List<LeaderboardEntry> cached = List.of(new LeaderboardEntry("u1", "앨리스", 3, 40));
        when(ops.get(KEY)).thenReturn(objectMapper.writeValueAsString(cached));

        assertThat(service.top()).isEqualTo(cached);
        verifyNoInteractions(progressRepository, answerRepository);
    }

    @Test
    void cacheMiss_computesSortsLimitsTo20AndCachesFor60s() throws Exception {
        when(ops.get(KEY)).thenReturn(null);
        List<GameProgressRepository.UserClearCount> clears = new ArrayList<>();
        IntStream.range(0, 25).forEach(i -> clears.add(clear("u" + i, "name" + i, i % 3)));
        when(progressRepository.countClearedGroupedByUser()).thenReturn(clears);
        when(answerRepository.countCorrectGroupedByUser()).thenReturn(List.of(
                correct("u2", 7), correct("u5", 9), correct("u1", 100)));

        List<LeaderboardEntry> top = service.top();

        assertThat(top).hasSize(20);
        // clearedCount 2: u2,u5,u8,... → u5(9) before u2(7) before the rest (0)
        assertThat(top.get(0)).isEqualTo(new LeaderboardEntry("u5", "name5", 2, 9));
        assertThat(top.get(1)).isEqualTo(new LeaderboardEntry("u2", "name2", 2, 7));
        assertThat(top.get(2).clearedCount()).isEqualTo(2);
        assertThat(top.get(2).correctCount()).isZero();
        // u1 has the most correct answers but only clearedCount 1 → ranks after every clearedCount 2
        int u1Rank = top.stream().map(LeaderboardEntry::userId).toList().indexOf("u1");
        int lastTwo = (int) top.stream().filter(e -> e.clearedCount() == 2).count();
        assertThat(u1Rank).isEqualTo(lastTwo);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(ops).set(eq(KEY), json.capture(), eq(Duration.ofSeconds(60)));
        assertThat(json.getValue()).contains("\"userId\":\"u5\"");
    }

    @Test
    void redisDown_fallsBackToDb() {
        when(ops.get(KEY)).thenThrow(new RedisConnectionFailureException("down"));
        when(progressRepository.countClearedGroupedByUser()).thenReturn(List.of(clear("u1", "a", 1)));
        when(answerRepository.countCorrectGroupedByUser()).thenReturn(List.of());
        org.mockito.Mockito.doThrow(new RedisConnectionFailureException("down"))
                .when(ops).set(anyString(), anyString(), eq(Duration.ofSeconds(60)));

        assertThat(service.top()).containsExactly(new LeaderboardEntry("u1", "a", 1, 0));
    }

    private static GameProgressRepository.UserClearCount clear(String userId, String username, long cleared) {
        return new GameProgressRepository.UserClearCount() {
            public String getUserId() {
                return userId;
            }

            public String getUsername() {
                return username;
            }

            public long getClearedCount() {
                return cleared;
            }
        };
    }

    private static GameAnswerRepository.UserCorrectCount correct(String userId, long count) {
        return new GameAnswerRepository.UserCorrectCount() {
            public String getUserId() {
                return userId;
            }

            public long getCorrectCount() {
                return count;
            }
        };
    }
}
