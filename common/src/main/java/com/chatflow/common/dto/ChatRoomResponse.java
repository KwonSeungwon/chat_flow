package com.chatflow.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Wire DTO for a chat room returned by the REST endpoints
 * ({@code GET /api/chat/rooms}, {@code GET /api/chat/rooms/{id}},
 * {@code POST /api/chat/rooms}, etc.).
 *
 * <p>Mirrors the {@code ChatRoom} entity (chat-service) field-for-field
 * so the wire JSON is byte-identical to what the entity's Jackson
 * serialization previously emitted — <strong>except</strong> the derived
 * {@code full} key (from {@code isFull()}) and {@code password} (already
 * {@code @JsonProperty(WRITE_ONLY)} on the entity but eliminated here
 * entirely). The frontend's {@code ChatRoom.fromJson} is the sole
 * consumer of this shape.
 *
 * <p>{@code roomType} is a plain {@code String} (not the enum) because
 * the {@code common} module cannot import {@code RoomType} from
 * {@code chat-service}. The entity uses {@code @Enumerated(STRING)},
 * so Jackson already emitted the enum's {@code name()} string — no
 * wire change.
 *
 * <p>Stage 4-B mapper #4, following the pattern set by
 * {@code ChatMessageMapper}, {@code MessageEditHistoryMapper}, and
 * {@code ChatMessageResponseMapper}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatRoomResponse {
    private String id;
    private String name;
    private String description;
    private String color;
    private String externalId;
    private String roomType;
    @JsonProperty("isPrivate")
    private boolean isPrivate;
    private boolean allowInvites;
    private String allowedRoles;
    private Integer participantCount;
    private Integer maxParticipants;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime lastMessageAt;
    private String pinnedMessageId;
}
