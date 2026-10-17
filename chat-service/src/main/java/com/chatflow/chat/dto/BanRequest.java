package com.chatflow.chat.dto;

import jakarta.validation.constraints.Size;

public record BanRequest(
        @Size(max = 36, message = "userId는 36자를 초과할 수 없습니다") String userId,
        @Size(max = 255, message = "reason은 255자를 초과할 수 없습니다") String reason) {
}
