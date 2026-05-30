package com.chatflow.chat.mapper;

import com.chatflow.chat.entity.MessageEditHistoryEntity;
import com.chatflow.common.dto.MessageEditHistory;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;

import java.util.List;

/**
 * MapStruct mapper between persistence entity and wire DTO for message
 * edit history.  All four wire fields ({@code messageId},
 * {@code previousContent}, {@code editedAt}, {@code editedBy}) share
 * identical names and types in entity and DTO, so zero {@code @Mapping}
 * annotations are needed — MapStruct generates the copy code
 * automatically.
 *
 * <p>Stage 4-B mapper #2, following the foundation pattern set by
 * {@link ChatMessageMapper}.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface MessageEditHistoryMapper {

    MessageEditHistory toDto(MessageEditHistoryEntity entity);

    List<MessageEditHistory> toDtoList(List<MessageEditHistoryEntity> entities);
}
