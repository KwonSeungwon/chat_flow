package com.gossiplog.chat.game.repository;

import com.gossiplog.chat.game.entity.GameTopic;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GameTopicRepository extends JpaRepository<GameTopic, Long> {

    Optional<GameTopic> findByDungeonIdAndTitle(String dungeonId, String title);
}
