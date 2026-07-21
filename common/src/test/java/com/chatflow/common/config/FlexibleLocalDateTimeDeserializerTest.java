package com.chatflow.common.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class FlexibleLocalDateTimeDeserializerTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addDeserializer(LocalDateTime.class, new FlexibleLocalDateTimeDeserializer());
        mapper.registerModule(module);
    }

    @Test
    @DisplayName("no suffix: 2026-03-30T12:20:59 parses correctly")
    void noSuffix() throws JsonProcessingException {
        LocalDateTime result = mapper.readValue("\"2026-03-30T12:20:59\"", LocalDateTime.class);
        assertEquals(LocalDateTime.of(2026, 3, 30, 12, 20, 59), result);
    }

    @Test
    @DisplayName("millis: 2026-03-30T12:20:59.564 parses with nanos")
    void millis() throws JsonProcessingException {
        LocalDateTime result = mapper.readValue("\"2026-03-30T12:20:59.564\"", LocalDateTime.class);
        assertEquals(LocalDateTime.of(2026, 3, 30, 12, 20, 59, 564_000_000), result);
    }

    @Test
    @DisplayName("trailing Z: 2026-03-30T12:20:59Z strips Z and parses")
    void trailingZ() throws JsonProcessingException {
        LocalDateTime result = mapper.readValue("\"2026-03-30T12:20:59Z\"", LocalDateTime.class);
        assertEquals(LocalDateTime.of(2026, 3, 30, 12, 20, 59), result);
    }

    @Test
    @DisplayName("millis+Z: 2026-03-30T12:20:59.564Z strips Z and parses with nanos")
    void millisZ() throws JsonProcessingException {
        LocalDateTime result = mapper.readValue("\"2026-03-30T12:20:59.564Z\"", LocalDateTime.class);
        assertEquals(LocalDateTime.of(2026, 3, 30, 12, 20, 59, 564_000_000), result);
    }

    @Test
    @DisplayName("offset +09:00: 2026-03-30T12:20:59+09:00 returns local part (no zone conversion)")
    void offsetNoMillis() throws JsonProcessingException {
        LocalDateTime result = mapper.readValue("\"2026-03-30T12:20:59+09:00\"", LocalDateTime.class);
        assertEquals(LocalDateTime.of(2026, 3, 30, 12, 20, 59), result);
    }

    @Test
    @DisplayName("offset +09:00 with millis: 2026-03-30T12:20:59.564+09:00 returns local part")
    void offsetWithMillis() throws JsonProcessingException {
        LocalDateTime result = mapper.readValue("\"2026-03-30T12:20:59.564+09:00\"", LocalDateTime.class);
        assertEquals(LocalDateTime.of(2026, 3, 30, 12, 20, 59, 564_000_000), result);
    }

    @ParameterizedTest
    @DisplayName("various offsets parse without throwing")
    @CsvSource({
            "2026-03-30T12:20:59+00:00, 2026-03-30T12:20:59",
            "2026-03-30T12:20:59-05:00, 2026-03-30T12:20:59",
            "2026-03-30T12:20:59.123+05:30, 2026-03-30T12:20:59.123"
    })
    void variousOffsets(String input, String expectedStr) throws JsonProcessingException {
        LocalDateTime result = mapper.readValue("\"" + input + "\"", LocalDateTime.class);
        LocalDateTime expected = LocalDateTime.parse(expectedStr);
        assertEquals(expected, result);
    }
}
