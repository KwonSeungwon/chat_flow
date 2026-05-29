package com.chatflow.chat.controller;

import com.chatflow.chat.entity.ChatRoom;
import com.chatflow.chat.entity.RoomRole;
import com.chatflow.chat.exception.ForbiddenException;
import com.chatflow.chat.exception.UnauthorizedException;
import com.chatflow.chat.repository.RoomMemberRepository;
import com.chatflow.chat.service.room.ChatRoomService;
import com.chatflow.chat.service.room.RoomMembershipService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoomMembershipGuardTest {

    @Mock private ChatRoomService chatRoomService;
    @Mock private RoomMembershipService roomMembershipService;
    @Mock private RoomMemberRepository roomMemberRepository;

    @InjectMocks
    private RoomMembershipGuard guard;

    // -- 401 ------------------------------------------------------------------

    @Test
    @DisplayName("throws UnauthorizedException when userId is null")
    void throws_unauthorized_when_userId_null() {
        assertThatThrownBy(() -> guard.requireMember("room-1", null))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("인증이 필요합니다.");

        verifyNoInteractions(roomMemberRepository, chatRoomService, roomMembershipService);
    }

    @Test
    @DisplayName("throws UnauthorizedException when userId is blank")
    void throws_unauthorized_when_userId_blank() {
        assertThatThrownBy(() -> guard.requireMember("room-1", ""))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("인증이 필요합니다.");

        verifyNoInteractions(roomMemberRepository, chatRoomService, roomMembershipService);
    }

    // -- happy path: already a member -----------------------------------------

    @Test
    @DisplayName("does not throw when user is in room_members")
    void does_not_throw_when_user_is_in_room_members() {
        when(roomMemberRepository.existsByRoomIdAndUserId("room-1", "user-1"))
                .thenReturn(true);

        assertThatCode(() -> guard.requireMember("room-1", "user-1"))
                .doesNotThrowAnyException();
    }

    // -- legacy bridge: backfill OWNER ----------------------------------------

    @Test
    @DisplayName("does not throw and backfills OWNER when userId equals createdBy (legacy)")
    void does_not_throw_and_backfills_OWNER_when_userId_equals_createdBy_legacy() {
        when(roomMemberRepository.existsByRoomIdAndUserId("room-1", "creator-1"))
                .thenReturn(false);

        ChatRoom legacyRoom = ChatRoom.builder()
                .id("room-1")
                .name("Legacy Room")
                .createdBy("creator-1")
                .build();
        when(chatRoomService.getRoom("room-1")).thenReturn(Optional.of(legacyRoom));

        assertThatCode(() -> guard.requireMember("room-1", "creator-1"))
                .doesNotThrowAnyException();

        verify(roomMembershipService).addMemberIfAbsent("room-1", "creator-1", null, RoomRole.OWNER);
    }

    // -- 403 ------------------------------------------------------------------

    @Test
    @DisplayName("throws ForbiddenException when not member and not creator")
    void throws_forbidden_when_not_member_and_not_creator() {
        when(roomMemberRepository.existsByRoomIdAndUserId("room-1", "outsider"))
                .thenReturn(false);

        ChatRoom room = ChatRoom.builder()
                .id("room-1")
                .name("Private Room")
                .createdBy("other-user")
                .build();
        when(chatRoomService.getRoom("room-1")).thenReturn(Optional.of(room));

        assertThatThrownBy(() -> guard.requireMember("room-1", "outsider"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("방 멤버가 아닙니다.");
    }
}
