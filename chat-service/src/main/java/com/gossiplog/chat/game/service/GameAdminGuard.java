package com.gossiplog.chat.game.service;

import com.gossiplog.chat.game.exception.GameForbiddenException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Allow-list from env GAME_ADMIN_USER_IDS (comma separated). An empty list denies everyone. */
@Component
public class GameAdminGuard {

    private final Set<String> adminUserIds;

    public GameAdminGuard(@Value("${GAME_ADMIN_USER_IDS:}") String adminUserIds) {
        this.adminUserIds = Arrays.stream(adminUserIds == null ? new String[0] : adminUserIds.split(","))
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isAdmin(String userId) {
        return userId != null && adminUserIds.contains(userId);
    }

    public void requireAdmin(String userId) {
        if (!isAdmin(userId)) {
            throw new GameForbiddenException();
        }
    }
}
