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
}
