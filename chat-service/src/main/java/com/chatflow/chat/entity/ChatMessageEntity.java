package com.chatflow.chat.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "chat_messages", indexes = {
    @Index(name = "idx_chat_room_id", columnList = "chatRoomId"),
    @Index(name = "idx_timestamp", columnList = "timestamp")
})
public class ChatMessageEntity {

    @Id
    @Column(length = 36)
    private String messageId;

    @Column(nullable = false, length = 50)
    private String chatRoomId;

    @Column(length = 50)
    private String userId;

    @Column(nullable = false, length = 100)
    private String username;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false)
    private LocalDateTime timestamp;

    @Column(length = 20)
    private String type;

    @Builder.Default
    @Column(name = "is_ai_generated")
    private boolean isAiGenerated = false;
}
