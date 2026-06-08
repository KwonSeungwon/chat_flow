package com.chatflow.chat.mapper;

import com.chatflow.chat.dto.ScheduledMessageDto;
import com.chatflow.chat.entity.ScheduledMessageEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

/** Stage 4-B mapper #5: ScheduledMessageEntity → ScheduledMessageDto. */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface ScheduledMessageMapper {

    @Mapping(target = "status", expression = "java(entity.getStatus() != null ? entity.getStatus().name() : null)")
    ScheduledMessageDto toDto(ScheduledMessageEntity entity);
}
