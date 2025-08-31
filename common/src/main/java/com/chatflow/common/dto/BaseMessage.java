package com.chatflow.common.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

@Data
@SuperBuilder
public abstract class BaseMessage {
    private String id;
    private String chatRoomId;
    private String userId;
    private String username;
    private String content;
    
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime timestamp;
    
    private MessageType type;
    
    public enum MessageType {
        CHAT, JOIN, LEAVE, SYSTEM, AI_SUMMARY
    }
}