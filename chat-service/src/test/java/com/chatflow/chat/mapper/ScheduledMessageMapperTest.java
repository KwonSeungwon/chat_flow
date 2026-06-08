package com.chatflow.chat.mapper;

import com.chatflow.chat.dto.ScheduledMessageDto;
import com.chatflow.chat.entity.ScheduledMessageEntity;
import com.chatflow.chat.entity.ScheduledMessageEntity.ScheduledMessageStatus;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduledMessageMapperTest {

    private final ScheduledMessageMapper mapper =
            Mappers.getMapper(ScheduledMessageMapper.class);

    @Test
    void toDto_copies_fields_and_stringifies_status() {
        LocalDateTime scheduledAt = LocalDateTime.of(2026, 7, 1, 14, 0);
        LocalDateTime createdAt = LocalDateTime.of(2026, 6, 7, 10, 0);

        ScheduledMessageEntity entity = ScheduledMessageEntity.builder()
                .id(7L)
                .chatRoomId("room-42")
                .userId("user-1")
                .username("alice")
                .content("hello scheduled")
                .scheduledAt(scheduledAt)
                .status(ScheduledMessageStatus.PENDING)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .version(0L)
                .build();

        ScheduledMessageDto dto = mapper.toDto(entity);

        assertThat(dto.id()).isEqualTo(7L);
        assertThat(dto.chatRoomId()).isEqualTo("room-42");
        assertThat(dto.content()).isEqualTo("hello scheduled");
        assertThat(dto.scheduledAt()).isEqualTo(scheduledAt);
        assertThat(dto.createdAt()).isEqualTo(createdAt);
        assertThat(dto.status()).isEqualTo("PENDING");
    }

    @Test
    void toDto_returns_null_for_null_input() {
        assertThat(mapper.toDto(null)).isNull();
    }
}
