package com.chatflow.chat.controller;

import com.chatflow.chat.exception.ForbiddenException;
import com.chatflow.chat.exception.UnauthorizedException;
import com.chatflow.chat.repository.RoomMemberRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoomMembershipGuardTest {

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

        verifyNoInteractions(roomMemberRepository);
    }

    @Test
    @DisplayName("throws UnauthorizedException when userId is blank")
    void throws_unauthorized_when_userId_blank() {
        assertThatThrownBy(() -> guard.requireMember("room-1", ""))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("인증이 필요합니다.");

        verifyNoInteractions(roomMemberRepository);
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

    // -- 403 ------------------------------------------------------------------

    @Test
    @DisplayName("former creator without a room_members row is forbidden and nothing is backfilled (S-8)")
    void requireMember_formerCreatorWithoutRow_forbidden_noBackfill() {
        // e.g. the creator was kicked/banned or left: room.createdBy still names them,
        // but createdBy is no longer a membership signal.
        when(roomMemberRepository.existsByRoomIdAndUserId("room-1", "creator-1"))
                .thenReturn(false);

        assertThatThrownBy(() -> guard.requireMember("room-1", "creator-1"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("방 멤버가 아닙니다.");

        verify(roomMemberRepository, never()).save(any());
    }

    @Test
    @DisplayName("throws ForbiddenException when not a member")
    void throws_forbidden_when_not_member() {
        when(roomMemberRepository.existsByRoomIdAndUserId("room-1", "outsider"))
                .thenReturn(false);

        assertThatThrownBy(() -> guard.requireMember("room-1", "outsider"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("방 멤버가 아닙니다.");
    }
}
