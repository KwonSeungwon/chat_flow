package com.chatflow.chat.mapper;

import com.chatflow.chat.entity.ChatRoom;
import com.chatflow.common.dto.ChatRoomResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

import java.util.List;

/**
 * MapStruct mapper between the persistence entity {@link ChatRoom}
 * and the REST wire DTO {@link ChatRoomResponse}.
 *
 * <p>Most fields share identical names and types, so MapStruct copies
 * them automatically. Three explicit mappings are needed:
 * <ul>
 *   <li>{@code roomType}: the entity stores a {@code RoomType} enum,
 *       but the DTO uses a plain {@code String} because the {@code common}
 *       module cannot import the enum. The expression calls {@code name()}.</li>
 *   <li>{@code isPrivate}: Lombok generates an {@code isPrivate()} getter
 *       for the primitive {@code boolean}, but MapStruct resolves it as
 *       a property named {@code private} — the explicit expression bridges
 *       the name mismatch (same workaround as {@code isAiGenerated} in
 *       {@link ChatMessageResponseMapper}).</li>
 *   <li>{@code allowInvites}: same Lombok {@code is}-prefix issue —
 *       Lombok generates {@code isAllowInvites()} which MapStruct cannot
 *       auto-resolve to the {@code allowInvites} target.</li>
 * </ul>
 *
 * <p>The entity-only fields {@code password} and the derived
 * {@code isFull()} have no DTO counterpart and are dropped — that is
 * precisely the entity-on-wire leak this mapper closes.
 *
 * <p>Stage 4-B mapper #4, following {@link ChatMessageResponseMapper}.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface ChatRoomMapper {

    @Mapping(target = "roomType", expression = "java(room.getRoomType() != null ? room.getRoomType().name() : null)")
    @Mapping(target = "isPrivate", expression = "java(room.isPrivate())")
    @Mapping(target = "allowInvites", expression = "java(room.isAllowInvites())")
    ChatRoomResponse toResponse(ChatRoom room);

    List<ChatRoomResponse> toResponseList(List<ChatRoom> rooms);
}
