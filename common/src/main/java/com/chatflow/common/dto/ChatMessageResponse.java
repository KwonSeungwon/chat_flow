package com.chatflow.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Wire DTO for a persisted chat message returned by the REST history
 * endpoints ({@code GET /api/chat/rooms/{roomId}/messages} and
 * {@code .../messages/cursor}).
 *
 * <p>Mirrors {@code ChatMessageEntity} (chat-service) field-for-field so
 * the wire JSON is byte-identical to the entity Jackson previously emitted
 * — <strong>except</strong> the two surrogate keys the entity leaked via
 * {@code getId()} (a duplicate of {@code messageId}) and {@code isNew()}.
 * The frontend's {@code effectiveId} falls back to {@code messageId}, so
 * dropping {@code id} is contract-safe and never read {@code new}.
 *
 * <p>Distinct from {@link ChatMessage}: that DTO is the lean Kafka/STOMP
 * contract and intentionally omits persistence-only fields
 * ({@code deleted}, {@code edited}, {@code pinned}, {@code reactions}, …).
 * This response DTO carries them because the chat history UI renders them.
 *
 * <p>{@code reactions} stays a raw JSON {@code String} (as stored), which
 * the frontend's {@code parseReactions} already decodes.
 *
 * <p>Stage 4-B mapper #3, following the pattern set by
 * {@code ChatMessageMapper} and {@code MessageEditHistoryMapper}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageResponse {
    private String messageId;
    private String chatRoomId;
    private String userId;
    private String username;
    private String content;
    private LocalDateTime timestamp;
    private String type;
    private String priority;
    private boolean isAiGenerated;
    private String fileUrl;
    private String fileName;
    private String fileContentType;
    private String parentMessageId;
    private String parentMessagePreview;
    private String forwardedFrom;
    private boolean deleted;
    private boolean edited;
    private LocalDateTime editedAt;
    private boolean pinned;
    private String reactions;
}
