package com.chatflow.chat.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "scheduled_messages")
public class ScheduledMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String chatRoomId;

    @Column(nullable = false, length = 36)
    private String userId;

    @Column(nullable = false, length = 50)
    private String username;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** Delivery time as UTC wall-clock time (the column has no zone; UTC by convention). */
    @Column(nullable = false)
    private LocalDateTime scheduledAt;

    @Column(nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private ScheduledMessageStatus status = ScheduledMessageStatus.PENDING;

    @Column(length = 36)
    private String sentMessageId;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Version
    @Column(nullable = false)
    private Long version;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * PROCESSING is transient: a delivery transaction claims PENDING → PROCESSING and
     * ends in SENT or FAILED before it commits (a rollback restores PENDING).
     */
    public enum ScheduledMessageStatus { PENDING, PROCESSING, SENT, CANCELED, FAILED }
}
