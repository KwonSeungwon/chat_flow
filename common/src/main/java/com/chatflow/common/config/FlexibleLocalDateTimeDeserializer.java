package com.chatflow.common.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Handles ISO 8601 timestamps with optional milliseconds and Z suffix.
 * JavaScript's toISOString() produces "2026-03-30T12:20:59.564Z" which
 * LocalDateTime cannot parse due to the trailing 'Z' (UTC indicator).
 */
public class FlexibleLocalDateTimeDeserializer extends JsonDeserializer<LocalDateTime> {

    @Override
    public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        String text = p.getText().trim();
        if (text.endsWith("Z")) {
            text = text.substring(0, text.length() - 1);
        }
        return LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }
}
