package com.chatflow.chat.game.dto;

public record DungeonSummary(String id, String name, String buildingStyle, boolean open,
                             boolean clearedByMe, long clearCount) {
}
