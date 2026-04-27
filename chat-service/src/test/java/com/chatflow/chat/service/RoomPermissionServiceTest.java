package com.chatflow.chat.service;

import com.chatflow.chat.entity.ChatRoom;
import com.chatflow.chat.entity.RoomMemberEntity;
import com.chatflow.chat.entity.RoomRole;
import com.chatflow.chat.entity.RoomType;
import com.chatflow.chat.exception.PermissionDeniedException;
import com.chatflow.chat.exception.RoomTypeNotSupportedException;
import com.chatflow.chat.exception.SelfTargetNotAllowedException;
import com.chatflow.chat.repository.ChatRoomRepository;
import com.chatflow.chat.repository.RoomMemberRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoomPermissionServiceTest {

    @Mock
    private RoomMemberRepository roomMemberRepository;
    @Mock
    private ChatRoomRepository chatRoomRepository;

    @InjectMocks
    private RoomPermissionService roomPermissionService;

    private static final String ROOM_ID = "room-1";
    private static final String USER_ID = "user-1";

    private RoomMemberEntity memberWith(RoomRole role) {
        return RoomMemberEntity.builder()
                .roomId(ROOM_ID)
                .userId(USER_ID)
                .username("testuser")
                .role(role)
                .joinedAt(LocalDateTime.now())
                .build();
    }

    // ── getUserRole ─────────────────────────────────────────────

    @Test
    void getUserRole_memberExists_returnsRole() {
        when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, USER_ID))
                .thenReturn(Optional.of(memberWith(RoomRole.OWNER)));

        RoomRole result = roomPermissionService.getUserRole(ROOM_ID, USER_ID);

        assertEquals(RoomRole.OWNER, result);
    }

    @Test
    void getUserRole_memberNotExists_throwsPermissionDenied() {
        when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, USER_ID))
                .thenReturn(Optional.empty());

        assertThrows(PermissionDeniedException.class,
                () -> roomPermissionService.getUserRole(ROOM_ID, USER_ID));
    }

    // ── requireRole ─────────────────────────────────────────────

    @Test
    void requireRole_ownerAllowed_passes() {
        when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, USER_ID))
                .thenReturn(Optional.of(memberWith(RoomRole.OWNER)));

        assertDoesNotThrow(() ->
                roomPermissionService.requireRole(ROOM_ID, USER_ID, RoomRole.OWNER));
    }

    @Test
    void requireRole_ownerAndModAllowed_ownerPasses() {
        when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, USER_ID))
                .thenReturn(Optional.of(memberWith(RoomRole.OWNER)));

        assertDoesNotThrow(() ->
                roomPermissionService.requireRole(ROOM_ID, USER_ID, RoomRole.OWNER, RoomRole.MODERATOR));
    }

    @Test
    void requireRole_ownerAndModAllowed_modPasses() {
        when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, USER_ID))
                .thenReturn(Optional.of(memberWith(RoomRole.MODERATOR)));

        assertDoesNotThrow(() ->
                roomPermissionService.requireRole(ROOM_ID, USER_ID, RoomRole.OWNER, RoomRole.MODERATOR));
    }

    @Test
    void requireRole_ownerRequired_memberDenied() {
        when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, USER_ID))
                .thenReturn(Optional.of(memberWith(RoomRole.MEMBER)));

        assertThrows(PermissionDeniedException.class,
                () -> roomPermissionService.requireRole(ROOM_ID, USER_ID, RoomRole.OWNER));
    }

    @Test
    void requireRole_ownerAndModRequired_memberDenied() {
        when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, USER_ID))
                .thenReturn(Optional.of(memberWith(RoomRole.MEMBER)));

        assertThrows(PermissionDeniedException.class,
                () -> roomPermissionService.requireRole(ROOM_ID, USER_ID, RoomRole.OWNER, RoomRole.MODERATOR));
    }

    @Test
    void requireRole_ownerRequired_modDenied() {
        when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, USER_ID))
                .thenReturn(Optional.of(memberWith(RoomRole.MODERATOR)));

        assertThrows(PermissionDeniedException.class,
                () -> roomPermissionService.requireRole(ROOM_ID, USER_ID, RoomRole.OWNER));
    }

    @Test
    void requireRole_userNotInRoom_throwsPermissionDenied() {
        when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, USER_ID))
                .thenReturn(Optional.empty());

        assertThrows(PermissionDeniedException.class,
                () -> roomPermissionService.requireRole(ROOM_ID, USER_ID, RoomRole.OWNER));
    }

    // ── requireNotDmRoom ────────────────────────────────────────

    @Test
    void requireNotDmRoom_generalRoom_passes() {
        ChatRoom room = ChatRoom.builder().id(ROOM_ID).roomType(RoomType.GENERAL).build();
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));

        assertDoesNotThrow(() -> roomPermissionService.requireNotDmRoom(ROOM_ID));
    }

    @Test
    void requireNotDmRoom_directRoom_throws() {
        ChatRoom room = ChatRoom.builder().id(ROOM_ID).roomType(RoomType.DIRECT).build();
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));

        assertThrows(RoomTypeNotSupportedException.class,
                () -> roomPermissionService.requireNotDmRoom(ROOM_ID));
    }

    @Test
    void requireNotDmRoom_handoffRoom_passes() {
        ChatRoom room = ChatRoom.builder().id(ROOM_ID).roomType(RoomType.HANDOFF).build();
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));

        assertDoesNotThrow(() -> roomPermissionService.requireNotDmRoom(ROOM_ID));
    }

    @Test
    void requireNotDmRoom_roomNotFound_passes() {
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> roomPermissionService.requireNotDmRoom(ROOM_ID));
    }

    // ── requireNotSelfTarget ────────────────────────────────────

    @Test
    void requireNotSelfTarget_differentUsers_passes() {
        assertDoesNotThrow(() ->
                roomPermissionService.requireNotSelfTarget("user-1", "user-2"));
    }

    @Test
    void requireNotSelfTarget_sameUser_throws() {
        assertThrows(SelfTargetNotAllowedException.class,
                () -> roomPermissionService.requireNotSelfTarget("user-1", "user-1"));
    }
}
