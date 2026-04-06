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
public class ReadReceipt {
    private String userId;
    private String username;
    private String roomId;
    private String lastReadMessageId;
    private LocalDateTime timestamp;
}
