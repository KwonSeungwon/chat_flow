package com.gossiplog.chat.game.repository;

import com.gossiplog.chat.game.entity.GamePlayer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GamePlayerRepository extends JpaRepository<GamePlayer, String> {
}
