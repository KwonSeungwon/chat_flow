package com.chatflow.common.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class ChatMessage extends BaseMessage {
    private String messageId;

    /**
     * AI-generated message flag.
     *
     * Wire format: serialized as "isAiGenerated" (matches the frontend
     * convention and the project precedent set by ChatRoom.isPrivate).
     * The {@link JsonAlias} accepts the legacy "aiGenerated" key for
     * in-flight Kafka payloads and existing Redis cache entries that
     * were serialized before this annotation was added.
     */
    @JsonProperty("isAiGenerated")
    @JsonAlias({"aiGenerated"})
    private boolean isAiGenerated;

    /**
     * Soft-delete flag. When true, the message has been deleted by the
     * author. search-service uses this to remove the document from
     * Elasticsearch on receipt of a MESSAGE_DELETED outbox event.
     *
     * Defaults to false so existing Kafka payloads and cached entries
     * that predate this field are deserialized as non-deleted (backward
     * compatible).
     */
    @JsonProperty("isDeleted")
    @JsonAlias({"deleted"})
    private boolean isDeleted;
}
