package com.chatflow.common.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@EqualsAndHashCode(callSuper = true)
public class ChatMessage extends BaseMessage {
    private String messageId;
    private boolean isAiGenerated;
}