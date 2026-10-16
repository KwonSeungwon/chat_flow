package com.chatflow.chat.game.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One monster per topic; the boss of a dungeon has no topic and {@code boss = true}. */
@Entity
@Table(name = "game_monster")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GameMonster {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "topic_id")
    private Long topicId;

    @Column(name = "dungeon_id", nullable = false, length = 30)
    private String dungeonId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "sprite_key", nullable = false, length = 100)
    private String spriteKey;

    @Column(name = "is_boss", nullable = false)
    private boolean boss;
}
