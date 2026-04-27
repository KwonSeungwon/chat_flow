package com.chatflow.chat.service;

import com.chatflow.chat.entity.ChatRoom;
import com.chatflow.chat.entity.RoomBanEntity;
import com.chatflow.chat.entity.RoomMemberEntity;
import com.chatflow.chat.entity.RoomRole;
import com.chatflow.chat.entity.RoomType;
import com.chatflow.chat.exception.PermissionDeniedException;
import com.chatflow.chat.exception.RoomTypeNotSupportedException;
import com.chatflow.chat.exception.SelfTargetNotAllowedException;
import com.chatflow.chat.repository.ChatRoomRepository;
import com.chatflow.chat.repository.RoomBanRepository;
import com.chatflow.chat.repository.RoomMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoomBanServiceTest {

    @Mock private RoomBanRepository roomBanRepository;
    @Mock private RoomMemberRepository roomMemberRepository;
    @Mock private ChatRoomRepository chatRoomRepository;
    @Mock private SimpMessagingTemplate messagingTemplate;

    private RoomPermissionService roomPermissionService;
    private RoomBanService roomBanService;

    private static final String ROOM_ID = "room-1";
    private static final String OWNER_ID = "owner-1";
    private static final String MOD_ID = "mod-1";
    private static final String MEMBER_ID = "member-1";
    private static final String TARGET_ID = "target-1";

    @BeforeEach
    void setUp() {
        roomPermissionService = new RoomPermissionService(roomMemberRepository, chatRoomRepository);
        roomBanService = new RoomBanService(
                roomBanRepository, roomMemberRepository, roomPermissionService, messagingTemplate);
    }

    private RoomMemberEntity member(String userId, String username, RoomRole role) {
        return RoomMemberEntity.builder()
                .roomId(ROOM_ID)
                .userId(userId)
                .username(username)
                .role(role)
                .joinedAt(LocalDateTime.now())
                .build();
    }

    private void stubGeneralRoom() {
        ChatRoom room = ChatRoom.builder().id(ROOM_ID).roomType(RoomType.GENERAL).build();
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
    }

    // ── banUser ─────────────────────────────────────────────────

    @Test
    void banUser_happyPath_kicksAndInsertsBan() {
        stubGeneralRoom();
        RoomMemberEntity owner = member(OWNER_ID, "owner", RoomRole.OWNER);
        RoomMemberEntity target = member(TARGET_ID, "target", RoomRole.MEMBER);
        when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, OWNER_ID))
                .thenReturn(Optional.of(owner));
        when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, TARGET_ID))
                .thenReturn(Optional.of(target));
        when(roomMemberRepository.findByRoomId(ROOM_ID))
                .thenReturn(List.of(owner));

        roomBanService.banUser(ROOM_ID, OWNER_ID, TARGET_ID, "spam");

        // Verify kick
        verify(roomMemberRepository).deleteByRoomIdAndUserId(ROOM_ID, TARGET_ID);

        // Verify ban insert
        ArgumentCaptor<RoomBanEntity> banCaptor = ArgumentCaptor.forClass(RoomBanEntity.class);
        verify(roomBanRepository).save(banCaptor.capture());
        RoomBanEntity savedBan = banCaptor.getValue();
        assertEquals(ROOM_ID, savedBan.getRoomId());
        assertEquals(TARGET_ID, savedBan.getUserId());
        assertEquals(OWNER_ID, savedBan.getBannedBy());
        assertEquals("spam", savedBan.getReason());

        // Verify STOMP events
        verify(messagingTemplate).convertAndSendToUser(
                eq(TARGET_ID), eq("/queue/kicked"), argThat(payload -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) payload;
                    return "BANNED".equals(map.get("reason"));
                }));
        verify(messagingTemplate).convertAndSend(
                eq("/topic/chat/" + ROOM_ID + "/members"), any(Map.class));
    }

    @Test
    void banUser_ownerCannotBeBanned_throwsPermissionDenied() {
        stubGeneralRoom();
        RoomMemberEntity mod = member(MOD_ID, "mod", RoomRole.MODERATOR);
        RoomMemberEntity owner = member(OWNER_ID, "owner", RoomRole.OWNER);
        when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, MOD_ID))
                .thenReturn(Optional.of(mod));
        when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, OWNER_ID))
                .thenReturn(Optional.of(owner));

        assertThrows(PermissionDeniedException.class,
                () -> roomBanService.banUser(ROOM_ID, MOD_ID, OWNER_ID, "test"));

        verify(roomBanRepository, never()).save(any());
        verify(roomMemberRepository, never()).deleteByRoomIdAndUserId(anyString(), anyString());
    }

    @Test
    void banUser_selfBan_throwsSelfTarget() {
        stubGeneralRoom();
        RoomMemberEntity owner = member(OWNER_ID, "owner", RoomRole.OWNER);
        when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, OWNER_ID))
                .thenReturn(Optional.of(owner));

        assertThrows(SelfTargetNotAllowedException.class,
                () -> roomBanService.banUser(ROOM_ID, OWNER_ID, OWNER_ID, "test"));
    }

    @Test
    void banUser_memberCannotBan_throwsPermissionDenied() {
        stubGeneralRoom();
        RoomMemberEntity normalMember = member(MEMBER_ID, "member", RoomRole.MEMBER);
        when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, MEMBER_ID))
                .thenReturn(Optional.of(normalMember));

        assertThrows(PermissionDeniedException.class,
                () -> roomBanService.banUser(ROOM_ID, MEMBER_ID, TARGET_ID, "test"));
    }

    @Test
    void banUser_dmRoom_throwsRoomTypeNotSupported() {
        ChatRoom dmRoom = ChatRoom.builder().id(ROOM_ID).roomType(RoomType.DIRECT).build();
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Optional.of(dmRoom));

        assertThrows(RoomTypeNotSupportedException.class,
                () -> roomBanService.banUser(ROOM_ID, OWNER_ID, TARGET_ID, "test"));
    }

    // ── unbanUser ───────────────────────────────────────────────

    @Test
    void unbanUser_happyPath_removesBanRow() {
        stubGeneralRoom();
        RoomMemberEntity owner = member(OWNER_ID, "owner", RoomRole.OWNER);
        when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, OWNER_ID))
                .thenReturn(Optional.of(owner));

        roomBanService.unbanUser(ROOM_ID, OWNER_ID, TARGET_ID);

        verify(roomBanRepository).deleteByRoomIdAndUserId(ROOM_ID, TARGET_ID);
    }

    // ── isBanned ────────────────────────────────────────────────

    @Test
    void isBanned_banned_returnsTrue() {
        when(roomBanRepository.existsByRoomIdAndUserId(ROOM_ID, TARGET_ID)).thenReturn(true);

        assertTrue(roomBanService.isBanned(ROOM_ID, TARGET_ID));
    }

    @Test
    void isBanned_notBanned_returnsFalse() {
        when(roomBanRepository.existsByRoomIdAndUserId(ROOM_ID, TARGET_ID)).thenReturn(false);

        assertFalse(roomBanService.isBanned(ROOM_ID, TARGET_ID));
    }

    // ── listBans ────────────────────────────────────────────────

    @Test
    void listBans_ownerCanView() {
        RoomMemberEntity owner = member(OWNER_ID, "owner", RoomRole.OWNER);
        when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, OWNER_ID))
                .thenReturn(Optional.of(owner));

        RoomBanEntity ban = RoomBanEntity.builder()
                .roomId(ROOM_ID).userId(TARGET_ID)
                .bannedBy(OWNER_ID).reason("spam")
                .bannedAt(LocalDateTime.now()).build();
        when(roomBanRepository.findByRoomId(ROOM_ID)).thenReturn(List.of(ban));

        List<RoomBanEntity> result = roomBanService.listBans(ROOM_ID, OWNER_ID);

        assertEquals(1, result.size());
        assertEquals(TARGET_ID, result.get(0).getUserId());
    }

    @Test
    void listBans_modCanView() {
        RoomMemberEntity mod = member(MOD_ID, "mod", RoomRole.MODERATOR);
        when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, MOD_ID))
                .thenReturn(Optional.of(mod));
        when(roomBanRepository.findByRoomId(ROOM_ID)).thenReturn(List.of());

        List<RoomBanEntity> result = roomBanService.listBans(ROOM_ID, MOD_ID);

        assertNotNull(result);
    }

    @Test
    void listBans_memberCannotView_throwsPermissionDenied() {
        RoomMemberEntity normalMember = member(MEMBER_ID, "member", RoomRole.MEMBER);
        when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, MEMBER_ID))
                .thenReturn(Optional.of(normalMember));

        assertThrows(PermissionDeniedException.class,
                () -> roomBanService.listBans(ROOM_ID, MEMBER_ID));
    }
}
