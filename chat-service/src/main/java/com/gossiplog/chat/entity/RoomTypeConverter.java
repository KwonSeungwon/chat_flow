package com.gossiplog.chat.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;

/**
 * Stores {@link RoomType} by name, like {@code @Enumerated(STRING)}, but reads an
 * unknown or retired value as {@link RoomType#GENERAL}. During a rolling update an
 * old pod can still write {@code HANDOFF}; without this the new pod would fail to
 * load that row and the room list would 500.
 */
@Slf4j
@Converter
public class RoomTypeConverter implements AttributeConverter<RoomType, String> {

    @Override
    public String convertToDatabaseColumn(RoomType attribute) {
        return attribute != null ? attribute.name() : null;
    }

    @Override
    public RoomType convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;
        try {
            return RoomType.valueOf(dbData);
        } catch (IllegalArgumentException e) {
            log.debug("Legacy room_type {} read as GENERAL", dbData);
            return RoomType.GENERAL;
        }
    }
}
