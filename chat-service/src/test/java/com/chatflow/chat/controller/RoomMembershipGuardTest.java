package com.chatflow.chat.controller;

import com.chatflow.chat.entity.ChatRoom;
import com.chatflow.chat.entity.RoomRole;
import com.chatflow.chat.repository.RoomMemberRepository;
import com.chatflow.chat.service.ChatRoomService;
import com.chatflow.chat.service.RoomMembershipService;
import com.chatflow.common.dto.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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

    // ── 401 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("returns 401 when userId is null or blank")
    void returns_401_when_userId_null_or_blank() {
        // null userId
        ResponseEntity<ApiResponse<?>> nullResult = guard.requireMember("room-1", null);
        assertThat(nullResult).isNotNull();
        assertThat(nullResult.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(nullResult.getBody()).isNotNull();
        assertThat(nullResult.getBody().isSuccess()).isFalse();
        assertThat(nullResult.getBody().getMessage()).isEqualTo("인증이 필요합니다.");

        // blank userId
        ResponseEntity<ApiResponse<?>> blankResult = guard.requireMember("room-1", "");
        assertThat(blankResult).isNotNull();
        assertThat(blankResult.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(blankResult.getBody()).isNotNull();
        assertThat(blankResult.getBody().isSuccess()).isFalse();
        assertThat(blankResult.getBody().getMessage()).isEqualTo("인증이 필요합니다.");

        verifyNoInteractions(roomMemberRepository, chatRoomService, roomMembershipService);
    }

    // ── happy path: already a member ────────────────────────────

    @Test
    @DisplayName("returns null when user is in room_members")
    void returns_null_when_user_is_in_room_members() {
        when(roomMemberRepository.existsByRoomIdAndUserId("room-1", "user-1"))
                .thenReturn(true);

        ResponseEntity<ApiResponse<?>> result = guard.requireMember("room-1", "user-1");

        assertThat(result).isNull();
    }

    // ── legacy bridge: backfill OWNER ───────────────────────────

    @Test
    @DisplayName("returns null and backfills OWNER when userId equals createdBy (legacy)")
    void returns_null_and_backfills_OWNER_when_userId_equals_createdBy_legacy() {
        when(roomMemberRepository.existsByRoomIdAndUserId("room-1", "creator-1"))
                .thenReturn(false);

        ChatRoom legacyRoom = ChatRoom.builder()
                .id("room-1")
                .name("Legacy Room")
                .createdBy("creator-1")
                .build();
        when(chatRoomService.getRoom("room-1")).thenReturn(Optional.of(legacyRoom));

        ResponseEntity<ApiResponse<?>> result = guard.requireMember("room-1", "creator-1");

        assertThat(result).isNull();
        verify(roomMembershipService).addMemberIfAbsent("room-1", "creator-1", null, RoomRole.OWNER);
    }

    // ── 403 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("returns 403 when not member and not creator")
    void returns_403_when_not_member_and_not_creator() {
        when(roomMemberRepository.existsByRoomIdAndUserId("room-1", "outsider"))
                .thenReturn(false);

        ChatRoom room = ChatRoom.builder()
                .id("room-1")
                .name("Private Room")
                .createdBy("other-user")
                .build();
        when(chatRoomService.getRoom("room-1")).thenReturn(Optional.of(room));

        ResponseEntity<ApiResponse<?>> result = guard.requireMember("room-1", "outsider");

        assertThat(result).isNotNull();
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().isSuccess()).isFalse();
        assertThat(result.getBody().getMessage()).isEqualTo("방 멤버가 아닙니다.");
    }
}
