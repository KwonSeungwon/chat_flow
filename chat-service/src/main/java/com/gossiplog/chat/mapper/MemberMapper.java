package com.gossiplog.chat.mapper;

import com.gossiplog.chat.dto.MemberDto;
import com.gossiplog.chat.entity.RoomMemberEntity;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;

import java.util.List;

/** Stage 4-B mapper #6: RoomMemberEntity → MemberDto (pure copy). */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface MemberMapper {
    MemberDto toDto(RoomMemberEntity entity);
    List<MemberDto> toDtoList(List<RoomMemberEntity> entities);
}
