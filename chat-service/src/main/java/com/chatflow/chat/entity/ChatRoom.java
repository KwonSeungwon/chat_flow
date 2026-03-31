package com.chatflow.chat.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
@ToString(exclude = {})
@Entity
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
    @Column(name = "is_private")
    private boolean isPrivate = false;

    @Builder.Default
    @Column(name = "allow_invites")
    private boolean allowInvites = true;

    @Builder.Default
    @Column(name = "participant_count")
    private int participantCount = 0;

    @Builder.Default
    @Column(name = "max_participants")
    private int maxParticipants = 10;

    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
