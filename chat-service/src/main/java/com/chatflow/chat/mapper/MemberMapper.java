package com.chatflow.chat.mapper;

import com.chatflow.chat.dto.MemberDto;
import com.chatflow.chat.entity.RoomMemberEntity;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;

import java.util.List;

/** Stage 4-B mapper #6: RoomMemberEntity → MemberDto (pure copy). */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface MemberMapper {
    MemberDto toDto(RoomMemberEntity entity);
    List<MemberDto> toDtoList(List<RoomMemberEntity> entities);
}
