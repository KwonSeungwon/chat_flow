package com.chatflow.chat.mapper;

import com.chatflow.chat.entity.ChatRoom;
import com.chatflow.chat.entity.RoomType;
import com.chatflow.common.dto.ChatRoomResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChatRoomMapperTest {

    private final ChatRoomMapper mapper =
            Mappers.getMapper(ChatRoomMapper.class);

    private ChatRoom fullEntity() {
        return ChatRoom.builder()
                .id("room-1")
                .name("General Chat")
                .description("A general chat room")
                .color("#6366f1")
                .externalId("ext-123")
                .roomType(RoomType.GENERAL)
                .isPrivate(true)
                .password("secret-hashed")
                .allowInvites(false)
                .allowedRoles("ADMIN,MOD")
                .participantCount(5)
                .maxParticipants(10)
                .createdBy("user-1")
                .createdAt(LocalDateTime.of(2026, 6, 7, 9, 30))
                .lastMessageAt(LocalDateTime.of(2026, 6, 7, 10, 0))
                .pinnedMessageId("msg-42")
                .build();
    }

    @Test
    void toResponse_copies_all_wire_fields() {
        ChatRoomResponse dto = mapper.toResponse(fullEntity());

        assertThat(dto.getId()).isEqualTo("room-1");
        assertThat(dto.getName()).isEqualTo("General Chat");
        assertThat(dto.getDescription()).isEqualTo("A general chat room");
        assertThat(dto.getColor()).isEqualTo("#6366f1");
        assertThat(dto.getExternalId()).isEqualTo("ext-123");
        assertThat(dto.getRoomType()).isEqualTo("GENERAL");
        assertThat(dto.isPrivate()).isTrue();
        assertThat(dto.isAllowInvites()).isFalse();
        assertThat(dto.getAllowedRoles()).isEqualTo("ADMIN,MOD");
        assertThat(dto.getParticipantCount()).isEqualTo(5);
        assertThat(dto.getMaxParticipants()).isEqualTo(10);
        assertThat(dto.getCreatedBy()).isEqualTo("user-1");
        assertThat(dto.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 6, 7, 9, 30));
        assertThat(dto.getLastMessageAt()).isEqualTo(LocalDateTime.of(2026, 6, 7, 10, 0));
        assertThat(dto.getPinnedMessageId()).isEqualTo("msg-42");
    }

    @Test
    void toResponse_returns_null_for_null_input() {
        assertThat(mapper.toResponse(null)).isNull();
    }

    @Test
    void toResponseList_maps_each_element() {
        List<ChatRoomResponse> dtos = mapper.toResponseList(List.of(
                ChatRoom.builder().id("r1").name("Room 1").participantCount(2)
                        .maxParticipants(10).createdAt(LocalDateTime.of(2026, 6, 7, 9, 0)).build(),
                ChatRoom.builder().id("r2").name("Room 2").participantCount(3)
                        .maxParticipants(10).createdAt(LocalDateTime.of(2026, 6, 7, 9, 1)).build()
        ));

        assertThat(dtos).hasSize(2);
        assertThat(dtos.get(0).getId()).isEqualTo("r1");
        assertThat(dtos.get(1).getName()).isEqualTo("Room 2");
    }

    /**
     * Leak-regression guard: the whole point of this DTO is that controllers
     * stop serializing the raw entity. The entity exposes {@code password}
     * (write-only but still on the class) and a derived {@code full} key from
     * {@code isFull()}; the DTO must carry neither. All keys the frontend's
     * {@code ChatRoom.fromJson} reads must remain present and unchanged.
     */
    @Test
    void serialized_json_omits_leaked_entity_keys_and_keeps_wire_keys() throws Exception {
        ObjectMapper om = new ObjectMapper().registerModule(new JavaTimeModule());
        ChatRoomResponse dto = mapper.toResponse(fullEntity());

        @SuppressWarnings("unchecked")
        Map<String, Object> json = om.convertValue(dto, Map.class);

        // leaked keys must be gone
        assertThat(json).doesNotContainKey("password");
        assertThat(json).doesNotContainKey("full");

        // keys the frontend ChatRoom.fromJson consumes must remain
        assertThat(json).containsKeys(
                "id", "name", "description", "color", "externalId",
                "roomType", "isPrivate", "allowInvites", "allowedRoles",
                "participantCount", "maxParticipants", "createdBy",
                "createdAt", "lastMessageAt", "pinnedMessageId");
    }
}
