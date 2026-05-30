package com.chatflow.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Wire DTO for one row of message edit history.  Mirrors the persistence
 * entity {@code MessageEditHistoryEntity} (chat-service) but without the
 * surrogate {@code id}, since clients don't need it.
 *
 * <p>JSON shape: {@code messageId}, {@code previousContent},
 * {@code editedAt}, {@code editedBy} — field names match the entity's
 * Jackson defaults so the wire format is byte-identical.  This change is
 * a Java-contract tightening only (controllers no longer leak entities),
 * not a wire-format change.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageEditHistory {
    private String messageId;
    private String previousContent;
    private LocalDateTime editedAt;
    private String editedBy;
}
