package com.gossiplog.chat.game.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Per-user game settings; currently the chosen avatar ("boy" or "girl"). */
@Entity
@Table(name = "game_player")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class GamePlayer {

    @Id
    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    @Column(length = 8, nullable = false)
    private String avatar;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
