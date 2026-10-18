package com.gossiplog.chat.game.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A building in the village. Seeded by V19 (8 rows). */
@Entity
@Table(name = "game_dungeon")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GameDungeon {

    @Id
    @Column(length = 30)
    private String id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "building_style", nullable = false, length = 30)
    private String buildingStyle;
}
