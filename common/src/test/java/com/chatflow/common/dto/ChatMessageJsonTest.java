package com.chatflow.common.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wire-format contract test for ChatMessage.isAiGenerated.
 *
 * Lombok's boolean is-prefix made Jackson serialize this field as
 * "aiGenerated" (the JavaBeans-stripped form). The frontend has always
 * sent "isAiGenerated", so the receive path silently dropped the flag.
 * The fix pinned @JsonProperty("isAiGenerated") and @JsonAlias for the
 * legacy key. These tests lock that contract — service-level tests
 * round-trip through symmetric Jackson and would not catch a regression.
 */
class ChatMessageJsonTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        // Match the configuration used by the Spring boot services.
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules(); // for LocalDateTime support
    }

    private ChatMessage sampleMessage(boolean aiFlag) {
        ChatMessage m = new ChatMessage();
        m.setMessageId("m-1");
        m.setChatRoomId("room-1");
        m.setUserId("ai");
        m.setUsername("AI");
        m.setContent("hello");
        m.setTimestamp(LocalDateTime.parse("2026-05-05T12:00:00"));
        m.setType(ChatMessage.MessageType.AI_SUMMARY);
        m.setAiGenerated(aiFlag);
        return m;
    }

    @Test
    void serializeWritesCanonicalKey() throws Exception {
        String json = objectMapper.writeValueAsString(sampleMessage(true));
        assertThat(json).contains("\"isAiGenerated\":true");
        assertThat(json).doesNotContain("\"aiGenerated\":");
    }

    @Test
    void deserializeAcceptsCanonicalKey() throws Exception {
        String json = "{\"messageId\":\"m-1\",\"chatRoomId\":\"r\","
                + "\"userId\":\"u\",\"username\":\"n\","
                + "\"content\":\"c\",\"timestamp\":\"2026-05-05T12:00:00\","
                + "\"type\":\"CHAT\",\"isAiGenerated\":true}";
        ChatMessage msg = objectMapper.readValue(json, ChatMessage.class);
        assertThat(msg.isAiGenerated()).isTrue();
    }

    @Test
    void deserializeAcceptsLegacyAliasForBackwardCompatibility() throws Exception {
        // Legacy in-flight Kafka payloads serialized before the fix used
        // Lombok's stripped wire key "aiGenerated". @JsonAlias bridges that.
        String json = "{\"messageId\":\"m-1\",\"chatRoomId\":\"r\","
                + "\"userId\":\"u\",\"username\":\"n\","
                + "\"content\":\"c\",\"timestamp\":\"2026-05-05T12:00:00\","
                + "\"type\":\"CHAT\",\"aiGenerated\":true}";
        ChatMessage msg = objectMapper.readValue(json, ChatMessage.class);
        assertThat(msg.isAiGenerated()).isTrue();
    }

    @Test
    void deserializeMissingKeyDefaultsToFalse() throws Exception {
        String json = "{\"messageId\":\"m-1\",\"chatRoomId\":\"r\","
                + "\"userId\":\"u\",\"username\":\"n\","
                + "\"content\":\"c\",\"timestamp\":\"2026-05-05T12:00:00\","
                + "\"type\":\"CHAT\"}";
        ChatMessage msg = objectMapper.readValue(json, ChatMessage.class);
        assertThat(msg.isAiGenerated()).isFalse();
    }

    @Test
    void roundTripPreservesValue() throws Exception {
        ChatMessage original = sampleMessage(true);
        String json = objectMapper.writeValueAsString(original);
        ChatMessage parsed = objectMapper.readValue(json, ChatMessage.class);
        assertThat(parsed.isAiGenerated()).isTrue();
    }
}
