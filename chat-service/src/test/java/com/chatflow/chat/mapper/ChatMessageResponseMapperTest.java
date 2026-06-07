package com.chatflow.chat.mapper;

import com.chatflow.chat.entity.ChatMessageEntity;
import com.chatflow.common.dto.ChatMessageResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChatMessageResponseMapperTest {

    private final ChatMessageResponseMapper mapper =
            Mappers.getMapper(ChatMessageResponseMapper.class);

    private ChatMessageEntity fullEntity() {
        return ChatMessageEntity.builder()
                .messageId("msg-1")
                .chatRoomId("room-1")
                .userId("user-1")
                .username("alice")
                .content("hello")
                .timestamp(LocalDateTime.of(2026, 6, 7, 9, 30))
                .type("CHAT")
                .priority("URGENT")
                .isAiGenerated(true)
                .fileUrl("http://x/f.png")
                .fileName("f.png")
                .fileContentType("image/png")
                .parentMessageId("parent-1")
                .parentMessagePreview("preview text")
                .forwardedFrom("orig-room")
                .deleted(true)
                .edited(true)
                .editedAt(LocalDateTime.of(2026, 6, 7, 10, 0))
                .pinned(true)
                .reactions("{\"👍\":[\"user-2\"]}")
                .build();
    }

    @Test
    void toResponse_copies_all_wire_fields() {
        ChatMessageResponse dto = mapper.toResponse(fullEntity());

        assertThat(dto.getMessageId()).isEqualTo("msg-1");
        assertThat(dto.getChatRoomId()).isEqualTo("room-1");
        assertThat(dto.getUserId()).isEqualTo("user-1");
        assertThat(dto.getUsername()).isEqualTo("alice");
        assertThat(dto.getContent()).isEqualTo("hello");
        assertThat(dto.getTimestamp()).isEqualTo(LocalDateTime.of(2026, 6, 7, 9, 30));
        assertThat(dto.getType()).isEqualTo("CHAT");
        assertThat(dto.getPriority()).isEqualTo("URGENT");
        assertThat(dto.isAiGenerated()).isTrue();
        assertThat(dto.getFileUrl()).isEqualTo("http://x/f.png");
        assertThat(dto.getFileName()).isEqualTo("f.png");
        assertThat(dto.getFileContentType()).isEqualTo("image/png");
        assertThat(dto.getParentMessageId()).isEqualTo("parent-1");
        assertThat(dto.getParentMessagePreview()).isEqualTo("preview text");
        assertThat(dto.getForwardedFrom()).isEqualTo("orig-room");
        assertThat(dto.isDeleted()).isTrue();
        assertThat(dto.isEdited()).isTrue();
        assertThat(dto.getEditedAt()).isEqualTo(LocalDateTime.of(2026, 6, 7, 10, 0));
        assertThat(dto.isPinned()).isTrue();
        assertThat(dto.getReactions()).isEqualTo("{\"👍\":[\"user-2\"]}");
    }

    @Test
    void toResponse_returns_null_for_null_input() {
        assertThat(mapper.toResponse(null)).isNull();
    }

    @Test
    void toResponseList_maps_each_element() {
        List<ChatMessageResponse> dtos = mapper.toResponseList(List.of(
                ChatMessageEntity.builder().messageId("m1").chatRoomId("r").username("a").content("one")
                        .timestamp(LocalDateTime.of(2026, 6, 7, 9, 0)).type("CHAT").build(),
                ChatMessageEntity.builder().messageId("m2").chatRoomId("r").username("a").content("two")
                        .timestamp(LocalDateTime.of(2026, 6, 7, 9, 1)).type("CHAT").build()
        ));

        assertThat(dtos).hasSize(2);
        assertThat(dtos.get(0).getMessageId()).isEqualTo("m1");
        assertThat(dtos.get(1).getContent()).isEqualTo("two");
    }

    @Test
    void toResponseList_handles_empty_list() {
        assertThat(mapper.toResponseList(List.of())).isEmpty();
    }

    /**
     * Leak-regression guard: the whole point of this DTO is that controllers
     * stop serializing the raw entity. The entity exposes two surrogate keys
     * via its {@code getId()} (= messageId) and {@code isNew()} getters; the
     * DTO must NOT carry them onto the wire. Field names the frontend reads
     * must remain present and unchanged.
     */
    @Test
    void serialized_json_omits_leaked_entity_keys_and_keeps_wire_keys() throws Exception {
        ObjectMapper om = new ObjectMapper().registerModule(new JavaTimeModule());
        ChatMessageResponse dto = mapper.toResponse(fullEntity());

        @SuppressWarnings("unchecked")
        Map<String, Object> json = om.convertValue(dto, Map.class);

        // leaked surrogate keys must be gone
        assertThat(json).doesNotContainKey("id");
        assertThat(json).doesNotContainKey("new");

        // keys the frontend ChatMessage.fromJson consumes must remain
        assertThat(json).containsKeys(
                "messageId", "chatRoomId", "userId", "username", "content",
                "timestamp", "type", "priority", "aiGenerated",
                "fileUrl", "fileName", "fileContentType",
                "parentMessageId", "parentMessagePreview", "forwardedFrom",
                "deleted", "edited", "editedAt", "pinned", "reactions");
    }
}
