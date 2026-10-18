package com.gossiplog.chat.game.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class SpacedRepetitionTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 16, 12, 0);

    @Test
    void wrongAnswer_isDueInOneDay_whateverTheStreak() {
        assertThat(SpacedRepetition.next(0, false, NOW)).isEqualTo(NOW.plusDays(1));
        assertThat(SpacedRepetition.next(5, false, NOW)).isEqualTo(NOW.plusDays(1));
    }

    @Test
    void correctAnswer_stepsOneThreeSevenDays() {
        assertThat(SpacedRepetition.next(0, true, NOW)).isEqualTo(NOW.plusDays(1));
        assertThat(SpacedRepetition.next(1, true, NOW)).isEqualTo(NOW.plusDays(3));
        assertThat(SpacedRepetition.next(2, true, NOW)).isEqualTo(NOW.plusDays(7));
        assertThat(SpacedRepetition.next(9, true, NOW)).isEqualTo(NOW.plusDays(7));
    }
}
