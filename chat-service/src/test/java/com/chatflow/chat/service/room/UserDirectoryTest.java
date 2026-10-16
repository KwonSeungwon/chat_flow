package com.chatflow.chat.service.room;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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
    void usersTableAbsent_localH2_fallsBackToTheClaimedName() {
        when(jdbc.queryForList(anyString(), eq(String.class), eq("u2")))
                .thenThrow(new BadSqlGrammarException("q", "SELECT", new SQLException("no table")));

        assertThat(directory.resolveUsername("u2", "bob")).isEqualTo("bob");
    }
}
