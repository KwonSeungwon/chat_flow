package com.chatflow.chat.mapper;

import com.chatflow.chat.entity.ChatMessageEntity;
import com.chatflow.common.dto.BaseMessage;
import com.chatflow.common.dto.ChatMessage;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.Named;

/**
 * MapStruct mapper between persistence entity and wire DTO for chat
 * messages. Generated implementation lives in build/generated/sources/
 * --- do not commit the generated *Impl class.
 *
 * <h3>Field-mismatch notes</h3>
 * <ul>
 *   <li>{@code type}: Entity stores {@code String}, DTO stores
 *       {@link BaseMessage.MessageType} enum. Custom qualifier methods
 *       handle the bidirectional conversion with null-safe fallback.</li>
 *   <li>Entity-only fields ({@code editedAt}, {@code pinned},
 *       {@code reactions}, {@code isNew}) are ignored on {@code toDto}
 *       because the DTO has no counterpart. {@code deleted} is mapped to
 *       the DTO's {@code isDeleted} field (Task 0.7) and {@code edited}
 *       is mapped to the DTO's {@code edited} field (Task 0.10).</li>
 *   <li>DTO-only fields ({@code id}, {@code roomType}) are ignored on
 *       {@code toEntity} because the entity has no counterpart.</li>
 * </ul>
 *
 * Stage 4-B foundation pilot. Future mappers (Room, EditHistory, etc.)
 * follow this same pattern: interface in com.chatflow.chat.mapper,
 * componentModel = SPRING so it autowires as a normal @Component.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface ChatMessageMapper {

    @Mapping(target = "type", source = "type", qualifiedByName = "stringToMessageType")
    @Mapping(target = "isAiGenerated", expression = "java(entity.isAiGenerated())")
    @Mapping(target = "isDeleted", expression = "java(entity.isDeleted())")
    @Mapping(target = "edited", expression = "java(entity.isEdited())")
    @Mapping(target = "roomType", ignore = true)
    @Mapping(target = "id", ignore = true)
    ChatMessage toDto(ChatMessageEntity entity);

    @Mapping(target = "type", source = "type", qualifiedByName = "messageTypeToString")
    @Mapping(target = "isAiGenerated", expression = "java(dto.isAiGenerated())")
    @Mapping(target = "deleted", ignore = true)
    @Mapping(target = "edited", ignore = true)
    @Mapping(target = "editedAt", ignore = true)
    @Mapping(target = "pinned", ignore = true)
    @Mapping(target = "reactions", ignore = true)
    @Mapping(target = "isNew", ignore = true)
    ChatMessageEntity toEntity(ChatMessage dto);

    @Named("stringToMessageType")
    default BaseMessage.MessageType stringToMessageType(String type) {
        if (type == null) return null;
        try {
            return BaseMessage.MessageType.valueOf(type);
        } catch (IllegalArgumentException e) {
            return BaseMessage.MessageType.CHAT;
        }
    }

    @Named("messageTypeToString")
    default String messageTypeToString(BaseMessage.MessageType type) {
        return type != null ? type.name() : "CHAT";
    }
}
