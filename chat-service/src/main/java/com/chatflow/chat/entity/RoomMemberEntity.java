package com.chatflow.chat.entity;

import jakarta.persistence.*;
import lombok.*;
import java.io.Serializable;
import java.time.LocalDateTime;

@Entity
@Table(name = "room_members",
    indexes = @Index(name = "idx_room_members_user_id", columnList = "user_id")
)
@IdClass(RoomMemberEntity.RoomMemberId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoomMemberEntity {

    @Id
    @Column(name = "room_id", length = 50, nullable = false)
    private String roomId;

    @Id
    @Column(name = "user_id", length = 36, nullable = false)
    private String userId;

    @Column(name = "username", length = 50, nullable = false)
    private String username;

    @Column(name = "joined_at", nullable = false)
    private LocalDateTime joinedAt;

    @PrePersist
    void prePersist() {
        if (joinedAt == null) joinedAt = LocalDateTime.now();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RoomMemberId implements Serializable {
        private String roomId;
        private String userId;
    }
}
