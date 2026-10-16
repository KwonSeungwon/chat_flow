package com.chatflow.chat.game.repository;

import com.chatflow.chat.game.entity.GameDungeon;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GameDungeonRepository extends JpaRepository<GameDungeon, String> {
}
