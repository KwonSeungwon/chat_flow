package com.gossiplog.chat.dto;

import java.time.LocalDateTime;

public record MuteResponse(LocalDateTime mutedUntil) {
}
