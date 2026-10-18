package com.gossiplog.chat.game.repository;

import com.gossiplog.chat.game.entity.GameMonster;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GameMonsterRepository extends JpaRepository<GameMonster, Long> {

    Optional<GameMonster> findByTopicId(Long topicId);

    Optional<GameMonster> findByDungeonIdAndBossTrue(String dungeonId);
}
