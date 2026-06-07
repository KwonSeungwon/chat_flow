package com.chatflow.chat.mapper;

import com.chatflow.chat.entity.ChatMessageEntity;
import com.chatflow.common.dto.ChatMessageResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

import java.util.List;

/**
 * MapStruct mapper between the persistence entity {@link ChatMessageEntity}
 * and the REST history wire DTO {@link ChatMessageResponse}.
 *
 * <p>Every {@code ChatMessageResponse} field shares an identical name and
 * type with the entity, so MapStruct copies them automatically. The lone
 * exception is the boolean {@code isAiGenerated}: Lombok's {@code is}-prefix
 * accessor naming makes MapStruct resolve the source/target property names
 * differently, so it is bridged with an explicit expression — the same
 * workaround used by {@link ChatMessageMapper}. The entity-only fields
 * {@code id} (surrogate duplicate of {@code messageId}) and {@code isNew}
 * (transient persistence flag) simply have no DTO counterpart and are
 * dropped — that is precisely the entity-on-wire leak this mapper closes.
 *
 * <p>Stage 4-B mapper #3, following {@link MessageEditHistoryMapper}.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface ChatMessageResponseMapper {

    @Mapping(target = "isAiGenerated", expression = "java(entity.isAiGenerated())")
    ChatMessageResponse toResponse(ChatMessageEntity entity);

    List<ChatMessageResponse> toResponseList(List<ChatMessageEntity> entities);
}
