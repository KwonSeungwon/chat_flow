package com.chatflow.chat.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
@Entity
@DynamicInsert
@DynamicUpdate
@Table(name = "chat_rooms", indexes = {
    @Index(name = "idx_chat_room_created_at", columnList = "created_at DESC"),
    @Index(name = "idx_chat_room_name", columnList = "name")
})
public class ChatRoom {

    @Id
    @Column(length = 50)
    private String id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(length = 7)
    private String color;

    @Column(name = "external_id", unique = true)
    private String externalId;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "room_type", length = 20)
    private RoomType roomType = RoomType.GENERAL;

    @Builder.Default
    @JsonProperty("isPrivate")
    @Column(name = "is_private")
    private boolean isPrivate = false;

    @Column(name = "password")
    private String password;

    @Builder.Default
    @Column(name = "allow_invites")
    private boolean allowInvites = true;

    @Builder.Default
    @Column(name = "participant_count")
    private Integer participantCount = 0;

    @Builder.Default
    @Column(name = "max_participants")
    private Integer maxParticipants = 10;

    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public boolean isFull() {
        int count = participantCount != null ? participantCount : 0;
        int max = maxParticipants != null ? maxParticipants : 10;
        return count >= max;
    }

    public static String nextOverflowName(String baseName, long existingCount) {
        return baseName + "-" + (existingCount + 1);
    }
}
