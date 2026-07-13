package com.chatflow.chat.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the JSON key contract of MentionItemDto.
 * The frontend mentions screen depends on exactly these keys;
 * a rename would break the screen silently.
 */
@DisplayName("MentionItemDto — JSON key contract")
class MentionItemDtoJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @Test
    @DisplayName("serialized JSON contains exactly the contracted keys")
    void jsonKeysMatchContract() throws Exception {
        MentionItemDto dto = new MentionItemDto(
                "msg-1",
                "room-1",
                "alice",
                "Hello @bob, how are you?",
                LocalDateTime.of(2026, 7, 4, 12, 0, 0),
                false
        );

        JsonNode tree = MAPPER.valueToTree(dto);

        Set<String> actualKeys = StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(tree.fieldNames(), Spliterator.ORDERED),
                false
        ).collect(Collectors.toSet());

        Set<String> expectedKeys = Set.of(
                "messageId", "chatRoomId", "fromUsername",
                "contentPreview", "timestamp", "read"
        );

        assertThat(actualKeys).isEqualTo(expectedKeys);
    }

    @Test
    @DisplayName("each field serializes to non-null value")
    void allFieldsPresent() throws Exception {
        MentionItemDto dto = new MentionItemDto(
                "msg-2",
                "room-2",
                "bob",
                "@everyone check this",
                LocalDateTime.of(2026, 7, 4, 14, 30, 0),
                true
        );

        String json = MAPPER.writeValueAsString(dto);

        assertThat(json).contains("\"messageId\":\"msg-2\"");
        assertThat(json).contains("\"chatRoomId\":\"room-2\"");
        assertThat(json).contains("\"fromUsername\":\"bob\"");
        assertThat(json).contains("\"contentPreview\":\"@everyone check this\"");
        assertThat(json).contains("\"read\":true");
        // timestamp is serialized as an array by default with JavaTimeModule;
        // verify it's present and non-null
        JsonNode tree = MAPPER.readTree(json);
        assertThat(tree.get("timestamp").isNull()).isFalse();
    }
}
