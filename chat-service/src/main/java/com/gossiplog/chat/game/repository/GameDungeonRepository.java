package com.gossiplog.chat.game.repository;

import com.gossiplog.chat.game.entity.GameDungeon;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GameDungeonRepository extends JpaRepository<GameDungeon, String> {
}
