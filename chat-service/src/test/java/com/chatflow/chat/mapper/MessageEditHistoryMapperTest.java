package com.chatflow.chat.mapper;

import com.chatflow.chat.entity.MessageEditHistoryEntity;
import com.chatflow.common.dto.MessageEditHistory;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MessageEditHistoryMapperTest {

    private final MessageEditHistoryMapper mapper =
            Mappers.getMapper(MessageEditHistoryMapper.class);

    @Test
    void toDto_copies_all_wire_fields() {
        MessageEditHistoryEntity entity = MessageEditHistoryEntity.builder()
                .id(42L)
                .messageId("msg-1")
                .previousContent("old text")
                .editedAt(LocalDateTime.of(2026, 5, 30, 12, 0))
                .editedBy("user-1")
                .build();

        MessageEditHistory dto = mapper.toDto(entity);

        assertThat(dto.getMessageId()).isEqualTo("msg-1");
        assertThat(dto.getPreviousContent()).isEqualTo("old text");
        assertThat(dto.getEditedAt()).isEqualTo(LocalDateTime.of(2026, 5, 30, 12, 0));
        assertThat(dto.getEditedBy()).isEqualTo("user-1");
    }

    @Test
    void toDtoList_maps_each_element() {
        List<MessageEditHistoryEntity> entities = List.of(
                MessageEditHistoryEntity.builder()
                        .messageId("msg-1")
                        .previousContent("v1")
                        .editedAt(LocalDateTime.of(2026, 5, 30, 10, 0))
                        .editedBy("user-1")
                        .build(),
                MessageEditHistoryEntity.builder()
                        .messageId("msg-1")
                        .previousContent("v2")
                        .editedAt(LocalDateTime.of(2026, 5, 30, 11, 0))
                        .editedBy("user-1")
                        .build()
        );

        List<MessageEditHistory> dtos = mapper.toDtoList(entities);

        assertThat(dtos).hasSize(2);
        assertThat(dtos.get(0).getPreviousContent()).isEqualTo("v1");
        assertThat(dtos.get(1).getPreviousContent()).isEqualTo("v2");
    }

    @Test
    void toDto_returns_null_for_null_input() {
        assertThat(mapper.toDto(null)).isNull();
    }
}
