package com.chatflow.chat.game.service;

import com.chatflow.chat.game.exception.GameForbiddenException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GameAdminGuardTest {

    @Test
    void emptyList_deniesEveryone() {
        GameAdminGuard guard = new GameAdminGuard("");
        assertThatThrownBy(() -> guard.requireAdmin("user-1"))
                .isInstanceOf(GameForbiddenException.class)
                .hasFieldOrPropertyWithValue("status", 403)
                .hasFieldOrPropertyWithValue("code", "GAME_FORBIDDEN");
    }

    @Test
    void blankEntriesOnly_deniesEveryone() {
        GameAdminGuard guard = new GameAdminGuard(" , ,");
        assertThatThrownBy(() -> guard.requireAdmin(""))
                .isInstanceOf(GameForbiddenException.class);
        assertThatThrownBy(() -> guard.requireAdmin(null))
                .isInstanceOf(GameForbiddenException.class);
    }

    @Test
    void userOutsideList_isDenied() {
        GameAdminGuard guard = new GameAdminGuard("admin-1");
        assertThatThrownBy(() -> guard.requireAdmin("user-2"))
                .isInstanceOf(GameForbiddenException.class);
    }

    @Test
    void isAdmin_reflectsTheList() {
        GameAdminGuard guard = new GameAdminGuard("admin-1, admin-2");
        assertThat(guard.isAdmin("admin-2")).isTrue();
        assertThat(guard.isAdmin("user-1")).isFalse();
        assertThat(guard.isAdmin(null)).isFalse();
    }

    @Test
    void userInList_passes() {
        GameAdminGuard guard = new GameAdminGuard("admin-1");
        assertThatCode(() -> guard.requireAdmin("admin-1")).doesNotThrowAnyException();
    }

    @Test
    void entriesAreTrimmedAndBlanksIgnored() {
        GameAdminGuard guard = new GameAdminGuard("  admin-1 ,, admin-2  ,");
        assertThatCode(() -> guard.requireAdmin("admin-1")).doesNotThrowAnyException();
        assertThatCode(() -> guard.requireAdmin("admin-2")).doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.requireAdmin(" admin-1 "))
                .isInstanceOf(GameForbiddenException.class);
    }
}
