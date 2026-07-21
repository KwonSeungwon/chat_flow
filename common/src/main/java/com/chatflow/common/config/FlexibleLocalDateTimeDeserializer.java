package com.chatflow.common.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Handles ISO 8601 timestamps with optional milliseconds, Z suffix, and
 * numeric UTC offsets (e.g. +09:00, -05:00).
 * <p>
 * JavaScript's toISOString() produces "2026-03-30T12:20:59.564Z" and some
 * clients send offset timestamps like "2026-03-30T12:20:59+09:00".
 * In all cases the <em>wall-clock local part</em> is kept (no zone conversion),
 * consistent with the original strip-Z-and-parse-as-local behavior.
 */
public class FlexibleLocalDateTimeDeserializer extends JsonDeserializer<LocalDateTime> {

    @Override
    public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        String text = p.getText().trim();
        try {
            // Handles Z and numeric offsets (+09:00) — takes the local (wall-clock) part,
            // consistent with the previous strip-Z-and-parse-as-local behavior (no zone conversion).
            return OffsetDateTime.parse(text).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // No offset present — parse as a local date-time (defensively strip a trailing Z).
            String local = text.endsWith("Z") ? text.substring(0, text.length() - 1) : text;
            return LocalDateTime.parse(local, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        }
    }
}
