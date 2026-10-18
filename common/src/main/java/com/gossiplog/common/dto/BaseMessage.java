package com.gossiplog.common.dto;

import com.gossiplog.common.config.FlexibleLocalDateTimeDeserializer;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public abstract class BaseMessage {
    private String id;

    @NotBlank(message = "chatRoomId는 필수입니다")
    @Size(max = 50, message = "chatRoomId는 50자를 초과할 수 없습니다")
    private String chatRoomId;

    @Size(max = 50, message = "userId는 50자를 초과할 수 없습니다")
    private String userId;

    @NotBlank(message = "username은 필수입니다")
    @Size(max = 100, message = "username은 100자를 초과할 수 없습니다")
    private String username;

    @NotBlank(message = "content는 필수입니다")
    @Size(max = 10000, message = "content는 10,000자를 초과할 수 없습니다")
    private String content;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss[.SSS]")
    @JsonDeserialize(using = FlexibleLocalDateTimeDeserializer.class)
    private LocalDateTime timestamp;

    @NotNull(message = "메시지 타입은 필수입니다")
    private MessageType type;

    private String roomType;

    // Caps mirror chat_messages column lengths so an overlong value is a 400, not a DB error.
    @Size(max = 512, message = "fileUrl은 512자를 초과할 수 없습니다")
    private String fileUrl;
    @Size(max = 255, message = "fileName은 255자를 초과할 수 없습니다")
    private String fileName;
    @Size(max = 100, message = "fileContentType은 100자를 초과할 수 없습니다")
    private String fileContentType;

    @Size(max = 36, message = "parentMessageId는 36자를 초과할 수 없습니다")
    private String parentMessageId;
    private String parentMessagePreview;  // "username: truncated content"

    @Size(max = 200, message = "forwardedFrom은 200자를 초과할 수 없습니다")
    private String forwardedFrom;  // "originalSender: truncatedContent" — 전달 메시지 원본 발신자+내용 요약

    public enum MessageType {
        CHAT, JOIN, LEAVE, SYSTEM, AI_SUMMARY, FILE
    }
}
