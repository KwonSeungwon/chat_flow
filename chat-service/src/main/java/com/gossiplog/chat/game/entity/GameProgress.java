package com.gossiplog.chat.game.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Per-user per-dungeon progress. {@code username} is kept for leaderboard display. */
@Entity
@Table(name = "game_progress")
@IdClass(GameProgressId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = {"userId", "dungeonId"})
public class GameProgress {

    @Id
    @Column(name = "user_id", length = 50, nullable = false)
    private String userId;

    @Id
    @Column(name = "dungeon_id", length = 30, nullable = false)
    private String dungeonId;

    @Column(length = 100)
    private String username;

    @Column(name = "cleared_at")
    private LocalDateTime clearedAt;

    @Column(name = "best_streak", nullable = false)
    private int bestStreak;
}
