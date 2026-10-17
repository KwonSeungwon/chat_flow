package com.chatflow.chat.service.room;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;

/**
 * Read-only lookup into gateway's {@code users} table (same PostgreSQL database
 * in prod; Flyway V5/V10/V16 already join it).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserDirectory {

    /** PostgreSQL undefined_table. */
    private static final String UNDEFINED_TABLE_POSTGRES = "42P01";
    /**
     * H2 (local dev DB) TABLE_OR_VIEW_NOT_FOUND error codes: 42102 (state 42S02),
     * 42103 (…_WITH_CANDIDATES, state 42S03), 42104 (…_DATABASE_EMPTY, state 42S04).
     */
    private static final Set<Integer> UNDEFINED_TABLE_H2_CODES = Set.of(42102, 42103, 42104);

    private final JdbcTemplate jdbcTemplate;

    /**
     * @return the stored username of {@code userId}; {@code null} when no such user;
     *         {@code claimed} only when the users table does not exist (local H2 dev DB).
     * @throws org.springframework.dao.DataAccessException any other lookup failure
     *         (permission denied, connection loss, ...) — never trust the client value then.
     */
    public String resolveUsername(String userId, String claimed) {
        try {
            List<String> rows = jdbcTemplate.queryForList(
                    "SELECT username FROM users WHERE user_id = ?", String.class, userId);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (BadSqlGrammarException e) {
            SQLException cause = e.getSQLException();
            if (!UNDEFINED_TABLE_POSTGRES.equals(cause.getSQLState())
                    && !UNDEFINED_TABLE_H2_CODES.contains(cause.getErrorCode())) {
                throw e;
            }
            log.warn("users table unavailable, trusting client username for {}: {}", userId, e.getMessage());
            return claimed;
        }
    }
}
