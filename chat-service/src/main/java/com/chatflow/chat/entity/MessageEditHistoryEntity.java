package com.chatflow.chat.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One row per message edit — captures the pre-edit content so it can be
 * shown in the "edit history" viewer. See V8__message_edit_history.sql.
 */
@Entity
@Table(name = "message_edit_history", indexes = {
        @Index(name = "idx_meh_message_id_edited_at",
               columnList = "messageId, editedAt DESC")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageEditHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", nullable = false, length = 36)
    private String messageId;

    @Column(name = "previous_content", nullable = false, columnDefinition = "TEXT")
    private String previousContent;

    @Column(name = "edited_at", nullable = false)
    private LocalDateTime editedAt;

    @Column(name = "edited_by", nullable = false, length = 50)
    private String editedBy;
}
