package com.chatflow.chat.service.room;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserDirectoryTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final UserDirectory directory = new UserDirectory(jdbc);

    @Test
    void returnsTheStoredUsername_notTheClaimedOne() {
        when(jdbc.queryForList(anyString(), eq(String.class), eq("u2"))).thenReturn(List.of("bob"));

        assertThat(directory.resolveUsername("u2", "spoofed")).isEqualTo("bob");
    }

    @Test
    void unknownUser_returnsNull() {
        when(jdbc.queryForList(anyString(), eq(String.class), eq("ghost"))).thenReturn(List.of());

        assertThat(directory.resolveUsername("ghost", "bob")).isNull();
    }

    @Test
    void usersTableAbsent_postgres42P01_fallsBackToTheClaimedName() {
        when(jdbc.queryForList(anyString(), eq(String.class), eq("u2")))
                .thenThrow(new BadSqlGrammarException("q", "SELECT", new SQLException("no table", "42P01")));

        assertThat(directory.resolveUsername("u2", "bob")).isEqualTo("bob");
    }

    /** Real local H2 (chat-service's dev DB) with other tables but no users table. */
    @Test
    void usersTableAbsent_realH2_fallsBackToTheClaimedName() {
        JdbcTemplate real = h2Jdbc("ud_nonempty");
        real.execute("CREATE TABLE chat_rooms (id VARCHAR(36))");
        UserDirectory h2 = new UserDirectory(real);

        assertThat(h2.resolveUsername("u2", "bob")).isEqualTo("bob");
    }

    /** Real H2 with no tables at all (H2 reports a different code: 42104). */
    @Test
    void usersTableAbsent_realEmptyH2_fallsBackToTheClaimedName() {
        UserDirectory h2 = new UserDirectory(h2Jdbc("ud_empty"));

        assertThat(h2.resolveUsername("u2", "bob")).isEqualTo("bob");
    }

    /** Real H2 where users exists but the query is otherwise broken — must not fall back. */
    @Test
    void otherSqlError_realH2_missingColumn_propagates() {
        JdbcTemplate real = h2Jdbc("ud_badcol");
        real.execute("CREATE TABLE users (id VARCHAR(36))");
        UserDirectory h2 = new UserDirectory(real);

        assertThatThrownBy(() -> h2.resolveUsername("u2", "bob"))
                .isInstanceOf(DataAccessException.class);
    }

    private static JdbcTemplate h2Jdbc(String db) {
        return new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:" + db + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", ""));
    }

    @Test
    void otherSqlError_permissionDenied42501_doesNotTrustTheClaimedName() {
        when(jdbc.queryForList(anyString(), eq(String.class), eq("u2")))
                .thenThrow(new BadSqlGrammarException("q", "SELECT", new SQLException("denied", "42501")));

        assertThatThrownBy(() -> directory.resolveUsername("u2", "bob"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void nonGrammarDataAccessError_propagates() {
        when(jdbc.queryForList(anyString(), eq(String.class), eq("u2")))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> directory.resolveUsername("u2", "bob"))
                .isInstanceOf(DataAccessException.class);
    }
}
