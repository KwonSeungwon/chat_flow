package com.chatflow.chat.service.moderation;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class MuteResultTest {

    @Test
    void constructor_stores_mutedUntil_and_accessor_returns_it() {
        LocalDateTime until = LocalDateTime.of(2026, 5, 24, 14, 30);
        MuteResult result = new MuteResult(until);

        assertEquals(until, result.mutedUntil());
    }

    @Test
    void constructor_accepts_null_mutedUntil() {
        MuteResult result = new MuteResult(null);

        assertNull(result.mutedUntil());
    }

    @Test
    void equals_and_hashCode_for_record() {
        LocalDateTime until = LocalDateTime.of(2026, 5, 24, 14, 30);
        MuteResult a = new MuteResult(until);
        MuteResult b = new MuteResult(until);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, new MuteResult(until.plusMinutes(1)));
    }
}
