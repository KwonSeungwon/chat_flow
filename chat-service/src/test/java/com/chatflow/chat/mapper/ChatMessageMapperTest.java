package com.chatflow.chat.mapper;

import com.chatflow.chat.entity.ChatMessageEntity;
import com.chatflow.common.dto.BaseMessage;
import com.chatflow.common.dto.ChatMessage;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for the MapStruct-generated mapper. Doesn't use Spring context ---
 * exercises the generated *Impl class directly via Mappers.getMapper().
 */
class ChatMessageMapperTest {

    private final ChatMessageMapper mapper = Mappers.getMapper(ChatMessageMapper.class);

    @Test
    void toDto_copies_core_fields() {
        ChatMessageEntity entity = ChatMessageEntity.builder()
                .messageId("msg-1")
                .chatRoomId("room-1")
                .userId("user-1")
                .username("alice")
                .content("hello")
                .type("CHAT")
                .priority("ROUTINE")
                .timestamp(LocalDateTime.of(2026, 5, 30, 12, 0))
                .isAiGenerated(false)
                .fileUrl("https://example.com/file.pdf")
                .fileName("file.pdf")
                .fileContentType("application/pdf")
                .parentMessageId("parent-1")
                .parentMessagePreview("bob: previous message")
                .forwardedFrom("carol: forwarded content")
                .build();

        ChatMessage dto = mapper.toDto(entity);

        assertThat(dto.getMessageId()).isEqualTo("msg-1");
        assertThat(dto.getChatRoomId()).isEqualTo("room-1");
        assertThat(dto.getUserId()).isEqualTo("user-1");
        assertThat(dto.getUsername()).isEqualTo("alice");
        assertThat(dto.getContent()).isEqualTo("hello");
        assertThat(dto.getType()).isEqualTo(BaseMessage.MessageType.CHAT);
        assertThat(dto.getPriority()).isEqualTo("ROUTINE");
        assertThat(dto.getTimestamp()).isEqualTo(LocalDateTime.of(2026, 5, 30, 12, 0));
        assertThat(dto.isAiGenerated()).isFalse();
        assertThat(dto.getFileUrl()).isEqualTo("https://example.com/file.pdf");
        assertThat(dto.getFileName()).isEqualTo("file.pdf");
        assertThat(dto.getFileContentType()).isEqualTo("application/pdf");
        assertThat(dto.getParentMessageId()).isEqualTo("parent-1");
        assertThat(dto.getParentMessagePreview()).isEqualTo("bob: previous message");
        assertThat(dto.getForwardedFrom()).isEqualTo("carol: forwarded content");
    }

    @Test
    void toDto_maps_aiGenerated_flag() {
        ChatMessageEntity entity = ChatMessageEntity.builder()
                .messageId("msg-ai")
                .chatRoomId("room-1")
                .userId("system")
                .username("AI")
                .content("summary")
                .type("AI_SUMMARY")
                .timestamp(LocalDateTime.now())
                .isAiGenerated(true)
                .build();

        ChatMessage dto = mapper.toDto(entity);

        assertThat(dto.isAiGenerated()).isTrue();
        assertThat(dto.getType()).isEqualTo(BaseMessage.MessageType.AI_SUMMARY);
    }

    @Test
    void toDto_returns_null_for_null_input() {
        assertThat(mapper.toDto(null)).isNull();
    }

    @Test
    void toEntity_copies_core_fields() {
        ChatMessage dto = ChatMessage.builder()
                .messageId("msg-2")
                .chatRoomId("room-2")
                .userId("user-2")
                .username("bob")
                .content("world")
                .type(BaseMessage.MessageType.FILE)
                .priority("URGENT")
                .timestamp(LocalDateTime.of(2026, 6, 1, 9, 30))
                .isAiGenerated(false)
                .fileUrl("https://example.com/doc.txt")
                .fileName("doc.txt")
                .fileContentType("text/plain")
                .parentMessageId("parent-2")
                .parentMessagePreview("alice: original")
                .forwardedFrom("dave: forwarded")
                .build();

        ChatMessageEntity entity = mapper.toEntity(dto);

        assertThat(entity.getMessageId()).isEqualTo("msg-2");
        assertThat(entity.getChatRoomId()).isEqualTo("room-2");
        assertThat(entity.getUserId()).isEqualTo("user-2");
        assertThat(entity.getUsername()).isEqualTo("bob");
        assertThat(entity.getContent()).isEqualTo("world");
        assertThat(entity.getType()).isEqualTo("FILE");
        assertThat(entity.getPriority()).isEqualTo("URGENT");
        assertThat(entity.getTimestamp()).isEqualTo(LocalDateTime.of(2026, 6, 1, 9, 30));
        assertThat(entity.isAiGenerated()).isFalse();
        assertThat(entity.getFileUrl()).isEqualTo("https://example.com/doc.txt");
        assertThat(entity.getFileName()).isEqualTo("doc.txt");
        assertThat(entity.getFileContentType()).isEqualTo("text/plain");
        assertThat(entity.getParentMessageId()).isEqualTo("parent-2");
        assertThat(entity.getParentMessagePreview()).isEqualTo("alice: original");
        assertThat(entity.getForwardedFrom()).isEqualTo("dave: forwarded");
    }

    @Test
    void toEntity_null_type_defaults_to_CHAT_string() {
        ChatMessage dto = ChatMessage.builder()
                .messageId("msg-3")
                .chatRoomId("room-3")
                .userId("user-3")
                .username("carol")
                .content("no type set")
                .build();

        ChatMessageEntity entity = mapper.toEntity(dto);

        assertThat(entity.getType()).isEqualTo("CHAT");
    }

    @Test
    void toDto_unknown_type_string_defaults_to_CHAT() {
        ChatMessageEntity entity = ChatMessageEntity.builder()
                .messageId("msg-4")
                .chatRoomId("room-4")
                .userId("user-4")
                .username("dave")
                .content("bad type")
                .type("NONEXISTENT_TYPE")
                .timestamp(LocalDateTime.now())
                .build();

        ChatMessage dto = mapper.toDto(entity);

        assertThat(dto.getType()).isEqualTo(BaseMessage.MessageType.CHAT);
    }

    @Test
    void toDto_maps_deleted_flag() {
        ChatMessageEntity entity = ChatMessageEntity.builder()
                .messageId("msg-del")
                .chatRoomId("room-1")
                .userId("user-1")
                .username("alice")
                .content("삭제된 메시지입니다.")
                .type("CHAT")
                .timestamp(LocalDateTime.now())
                .deleted(true)
                .build();

        ChatMessage dto = mapper.toDto(entity);

        assertThat(dto.isDeleted()).isTrue();
    }

    @Test
    void toDto_deleted_defaults_false_when_entity_not_deleted() {
        ChatMessageEntity entity = ChatMessageEntity.builder()
                .messageId("msg-live")
                .chatRoomId("room-1")
                .userId("user-1")
                .username("alice")
                .content("hello")
                .type("CHAT")
                .timestamp(LocalDateTime.now())
                .build();

        ChatMessage dto = mapper.toDto(entity);

        assertThat(dto.isDeleted()).isFalse();
    }

    @Test
    void toEntity_ignores_dto_deleted_flag() {
        ChatMessage dto = ChatMessage.builder()
                .messageId("msg-ign")
                .chatRoomId("room-1")
                .userId("user-1")
                .username("alice")
                .content("hello")
                .type(BaseMessage.MessageType.CHAT)
                .timestamp(LocalDateTime.now())
                .isDeleted(true)
                .build();

        ChatMessageEntity entity = mapper.toEntity(dto);

        // deleted is ignored on toEntity — entity's deleted flag is managed by the service layer
        assertThat(entity.isDeleted()).isFalse();
    }

    @Test
    void toEntity_returns_null_for_null_input() {
        assertThat(mapper.toEntity(null)).isNull();
    }

    @Test
    void roundtrip_preserves_data() {
        ChatMessage original = ChatMessage.builder()
                .messageId("msg-rt")
                .chatRoomId("room-rt")
                .userId("user-rt")
                .username("roundtrip-user")
                .content("roundtrip content")
                .type(BaseMessage.MessageType.CHAT)
                .priority("ROUTINE")
                .timestamp(LocalDateTime.of(2026, 5, 30, 15, 45))
                .isAiGenerated(true)
                .build();

        ChatMessageEntity entity = mapper.toEntity(original);
        ChatMessage restored = mapper.toDto(entity);

        assertThat(restored.getMessageId()).isEqualTo(original.getMessageId());
        assertThat(restored.getChatRoomId()).isEqualTo(original.getChatRoomId());
        assertThat(restored.getUserId()).isEqualTo(original.getUserId());
        assertThat(restored.getUsername()).isEqualTo(original.getUsername());
        assertThat(restored.getContent()).isEqualTo(original.getContent());
        assertThat(restored.getType()).isEqualTo(original.getType());
        assertThat(restored.getPriority()).isEqualTo(original.getPriority());
        assertThat(restored.getTimestamp()).isEqualTo(original.getTimestamp());
        assertThat(restored.isAiGenerated()).isEqualTo(original.isAiGenerated());
    }
}
