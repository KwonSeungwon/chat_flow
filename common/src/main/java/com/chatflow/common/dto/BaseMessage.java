package com.chatflow.common.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
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

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime timestamp;

    @NotNull(message = "메시지 타입은 필수입니다")
    private MessageType type;

    public enum MessageType {
        CHAT, JOIN, LEAVE, SYSTEM, AI_SUMMARY
    }
}
