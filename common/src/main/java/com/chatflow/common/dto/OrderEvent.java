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
public class OrderEvent {
    private String orderId;
    private String patientId;
    private String patientName;
    private OrderType orderType;
    private String description;
    private String roomId;
    private LocalDateTime timestamp;

    public enum OrderType {
        MEDICATION, LAB
    }
}
