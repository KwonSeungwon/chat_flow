package com.chatflow.chat.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "message_mentions",
       uniqueConstraints = @UniqueConstraint(columnNames = {"message_id", "mentioned_user_id"}))
@Getter @Setter @Builder
@NoArgsConstructor @AllArgsConstructor
public class MessageMentionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", nullable = false, length = 36)
    private String messageId;

    @Column(name = "room_id", nullable = false, length = 50)
    private String roomId;

    @Column(name = "mentioned_user_id", nullable = false, length = 36)
    private String mentionedUserId;

    @Column(name = "mentioned_username", nullable = false, length = 50)
    private String mentionedUsername;

    @Column(name = "from_username", nullable = false, length = 50)
    private String fromUsername;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "read", nullable = false)
    private boolean read;
}
