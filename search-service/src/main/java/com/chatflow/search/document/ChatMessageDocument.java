package com.chatflow.search.document;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatMessageDocument {

    private String id;

    private String messageId;

    private String chatRoomId;

    private String userId;

    private String username;

    private String content;

    private LocalDateTime timestamp;

    private String messageType;

    private boolean isAiGenerated;

    private String fileName;

    private String fileUrl;

    private String fileContentType;

    private String parentMessageId;

    private String parentMessagePreview;
}
