package com.chatflow.chat.mapper;

import com.chatflow.chat.dto.MemberDto;
import com.chatflow.chat.entity.RoomMemberEntity;
import com.chatflow.chat.entity.RoomRole;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MemberMapperTest {

    private final MemberMapper mapper = Mappers.getMapper(MemberMapper.class);

    @Test
    void toDto_copies_all_fields() {
        RoomMemberEntity entity = RoomMemberEntity.builder()
                .roomId("room-1")
                .userId("u")
                .username("alice")
                .role(RoomRole.MODERATOR)
                .mutedUntil(LocalDateTime.of(2026, 6, 7, 15, 0))
                .build();

        MemberDto dto = mapper.toDto(entity);

        assertThat(dto.userId()).isEqualTo("u");
        assertThat(dto.username()).isEqualTo("alice");
        assertThat(dto.role()).isEqualTo(RoomRole.MODERATOR);
        assertThat(dto.mutedUntil()).isEqualTo(LocalDateTime.of(2026, 6, 7, 15, 0));
    }

    @Test
    void toDto_returns_null_for_null_input() {
        assertThat(mapper.toDto(null)).isNull();
    }

    @Test
    void toDtoList_maps_each() {
        List<RoomMemberEntity> entities = List.of(
                RoomMemberEntity.builder()
                        .roomId("room-1")
                        .userId("u1")
                        .username("owner-user")
                        .role(RoomRole.OWNER)
                        .build(),
                RoomMemberEntity.builder()
                        .roomId("room-1")
                        .userId("u2")
                        .username("member-user")
                        .role(RoomRole.MEMBER)
                        .build()
        );

        List<MemberDto> dtos = mapper.toDtoList(entities);

        assertThat(dtos).hasSize(2);
        assertThat(dtos.get(0).role()).isEqualTo(RoomRole.OWNER);
    }
}
