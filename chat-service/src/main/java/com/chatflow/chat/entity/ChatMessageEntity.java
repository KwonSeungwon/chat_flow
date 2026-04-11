package com.chatflow.chat.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.DynamicInsert;
import org.springframework.data.domain.Persistable;

import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "messageId")
@Entity
@DynamicInsert
@Table(name = "chat_messages", indexes = {
    @Index(name = "idx_chat_room_id", columnList = "chatRoomId"),
    @Index(name = "idx_timestamp", columnList = "timestamp"),
    @Index(name = "idx_chat_room_timestamp", columnList = "chatRoomId, timestamp DESC"),
    @Index(name = "idx_parent_message_id", columnList = "parentMessageId")
})
public class ChatMessageEntity implements Persistable<String> {

    @Id
    @Column(length = 36)
    private String messageId;

    @Transient
    @Builder.Default
    private boolean isNew = true;

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
    @Column(length = 10)
    private String priority = "ROUTINE";

    @Builder.Default
    @Column(name = "is_ai_generated")
    private boolean isAiGenerated = false;

    @Column(length = 512)
    private String fileUrl;

    @Column(length = 255)
    private String fileName;

    @Column(length = 100)
    private String fileContentType;

    @Column(length = 36)
    private String parentMessageId;

    @Column(length = 150)
    private String parentMessagePreview;

    @Builder.Default
    @Column(name = "is_deleted")
    private boolean deleted = false;

    @Override
    public String getId() {
        return messageId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }
}
