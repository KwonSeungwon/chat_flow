package com.gossiplog.chat.game.service;

import java.time.LocalDateTime;

/** Review interval: wrong → 1 day; correct → 1, 3, 7 days by the previous correct streak. */
public final class SpacedRepetition {

    private SpacedRepetition() {
    }

    public static LocalDateTime next(int previousCorrectStreak, boolean correct, LocalDateTime now) {
        if (!correct || previousCorrectStreak <= 0) {
            return now.plusDays(1);
        }
        return previousCorrectStreak == 1 ? now.plusDays(3) : now.plusDays(7);
    }
}
