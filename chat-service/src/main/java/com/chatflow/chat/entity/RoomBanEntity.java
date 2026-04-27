package com.chatflow.chat.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "room_bans")
@IdClass(RoomBanId.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoomBanEntity {

    @Id
    @Column(name = "room_id", length = 50, nullable = false)
    private String roomId;

    @Id
    @Column(name = "user_id", length = 36, nullable = false)
    private String userId;

    @Column(name = "banned_by", length = 36, nullable = false)
    private String bannedBy;

    @Column(name = "reason", length = 255)
    private String reason;

    @Column(name = "banned_at", nullable = false)
    private LocalDateTime bannedAt;

    @PrePersist
    void prePersist() {
        if (bannedAt == null) bannedAt = LocalDateTime.now();
    }
}
