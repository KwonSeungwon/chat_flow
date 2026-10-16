package com.chatflow.chat.service.room;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Read-only lookup into gateway's {@code users} table (same PostgreSQL database
 * in prod; Flyway V5/V10/V16 already join it).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserDirectory {

    private final JdbcTemplate jdbcTemplate;

    /**
     * @return the stored username of {@code userId}; {@code null} when no such user;
     *         {@code claimed} when the users table is not reachable (local H2 dev DB).
     */
    public String resolveUsername(String userId, String claimed) {
        try {
            List<String> rows = jdbcTemplate.queryForList(
                    "SELECT username FROM users WHERE user_id = ?", String.class, userId);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (BadSqlGrammarException e) {
            log.warn("users table unavailable, trusting client username for {}: {}", userId, e.getMessage());
            return claimed;
        }
    }
}
