package com.chatflow.chat.service.moderation;

import java.time.LocalDateTime;

public record MuteResult(LocalDateTime mutedUntil) {
}
