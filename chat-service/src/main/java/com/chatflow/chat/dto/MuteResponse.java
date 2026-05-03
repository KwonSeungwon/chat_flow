package com.chatflow.chat.dto;

import java.time.LocalDateTime;

public record MuteResponse(LocalDateTime mutedUntil) {
}
