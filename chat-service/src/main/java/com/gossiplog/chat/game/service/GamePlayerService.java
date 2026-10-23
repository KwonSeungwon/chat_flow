package com.gossiplog.chat.game.service;

import com.gossiplog.chat.game.entity.GamePlayer;
import com.gossiplog.chat.game.exception.GameException;
import com.gossiplog.chat.game.repository.GamePlayerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;

/** Stores the player's chosen avatar. */
@Service
@RequiredArgsConstructor
public class GamePlayerService {

    private static final Set<String> AVATARS = Set.of("boy", "girl");

    private final GamePlayerRepository playerRepository;

    @Transactional
    public void setAvatar(String userId, String avatar) {
        if (avatar == null || !AVATARS.contains(avatar)) {
            throw GameException.validation("avatar must be boy or girl");
        }
        GamePlayer player = playerRepository.findById(userId)
                .orElseGet(() -> new GamePlayer(userId, avatar, null));
        player.setAvatar(avatar);
        player.setUpdatedAt(Instant.now());
        playerRepository.save(player);
    }
}
