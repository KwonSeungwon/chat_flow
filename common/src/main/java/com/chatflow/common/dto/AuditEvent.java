package com.chatflow.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditEvent {
    private String eventId;
    private String eventType;
    private String userId;
    private String username;
    private String resourceId;
    private String roomId;
    private LocalDateTime timestamp;

    public static final String MESSAGE_READ = "MESSAGE_READ";
    public static final String ROOM_JOIN = "ROOM_JOIN";
    public static final String MESSAGE_SEARCH = "MESSAGE_SEARCH";
    public static final String ROOM_HIDDEN = "ROOM_HIDDEN";
    public static final String ROOM_HIDE_DENIED = "ROOM_HIDE_DENIED";
}
