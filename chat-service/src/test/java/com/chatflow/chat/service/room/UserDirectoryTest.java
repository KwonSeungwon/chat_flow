package com.chatflow.chat.service.room;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;

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

    @Test
    void usersTableAbsent_localH2_42S02_fallsBackToTheClaimedName() {
        when(jdbc.queryForList(anyString(), eq(String.class), eq("u2")))
                .thenThrow(new BadSqlGrammarException("q", "SELECT", new SQLException("no table", "42S02")));

        assertThat(directory.resolveUsername("u2", "bob")).isEqualTo("bob");
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
