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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
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
class MemberManagementServiceTest {

    @Mock private RoomMemberRepository roomMemberRepository;
    @Mock private ChatRoomRepository chatRoomRepository;
    @Mock private SimpMessagingTemplate messagingTemplate;

    private RoomPermissionService roomPermissionService;
    private MemberManagementService memberManagementService;

    private static final String ROOM_ID = "room-1";
    private static final String OWNER_ID = "owner-1";
    private static final String MOD_ID = "mod-1";
    private static final String MEMBER_ID = "member-1";
    private static final String TARGET_ID = "target-1";

    @BeforeEach
    void setUp() {
        roomPermissionService = new RoomPermissionService(roomMemberRepository, chatRoomRepository);
        memberManagementService = new MemberManagementService(
                roomMemberRepository, roomPermissionService, messagingTemplate);
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

    private void stubDmRoom() {
        ChatRoom room = ChatRoom.builder().id(ROOM_ID).roomType(RoomType.DIRECT).build();
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
    }

    // ── kickMember ──────────────────────────────────────────────

    @Nested
    class KickMemberTests {

        @Test
        void ownerKicksMember_success() {
            stubGeneralRoom();
            RoomMemberEntity owner = member(OWNER_ID, "owner", RoomRole.OWNER);
            RoomMemberEntity target = member(TARGET_ID, "target", RoomRole.MEMBER);
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, OWNER_ID))
                    .thenReturn(Optional.of(owner));
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, TARGET_ID))
                    .thenReturn(Optional.of(target));
            when(roomMemberRepository.findByRoomId(ROOM_ID))
                    .thenReturn(List.of(owner));

            memberManagementService.kickMember(ROOM_ID, OWNER_ID, TARGET_ID);

            verify(roomMemberRepository).deleteByRoomIdAndUserId(ROOM_ID, TARGET_ID);
            verify(messagingTemplate).convertAndSendToUser(
                    eq(TARGET_ID), eq("/queue/kicked"), any(Map.class));
            verify(messagingTemplate).convertAndSend(
                    eq("/topic/chat/" + ROOM_ID + "/members"), any(Map.class));
        }

        @Test
        void modKicksMember_success() {
            stubGeneralRoom();
            RoomMemberEntity mod = member(MOD_ID, "mod", RoomRole.MODERATOR);
            RoomMemberEntity target = member(TARGET_ID, "target", RoomRole.MEMBER);
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, MOD_ID))
                    .thenReturn(Optional.of(mod));
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, TARGET_ID))
                    .thenReturn(Optional.of(target));
            when(roomMemberRepository.findByRoomId(ROOM_ID))
                    .thenReturn(List.of(mod));

            memberManagementService.kickMember(ROOM_ID, MOD_ID, TARGET_ID);

            verify(roomMemberRepository).deleteByRoomIdAndUserId(ROOM_ID, TARGET_ID);
        }

        @Test
        void modCannotKickOwner_throwsPermissionDenied() {
            stubGeneralRoom();
            RoomMemberEntity mod = member(MOD_ID, "mod", RoomRole.MODERATOR);
            RoomMemberEntity owner = member(OWNER_ID, "owner", RoomRole.OWNER);
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, MOD_ID))
                    .thenReturn(Optional.of(mod));
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, OWNER_ID))
                    .thenReturn(Optional.of(owner));

            assertThrows(PermissionDeniedException.class,
                    () -> memberManagementService.kickMember(ROOM_ID, MOD_ID, OWNER_ID));
            verify(roomMemberRepository, never()).deleteByRoomIdAndUserId(anyString(), anyString());
        }

        @Test
        void ownerCannotSelfKick_throwsSelfTarget() {
            stubGeneralRoom();
            RoomMemberEntity owner = member(OWNER_ID, "owner", RoomRole.OWNER);
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, OWNER_ID))
                    .thenReturn(Optional.of(owner));

            assertThrows(SelfTargetNotAllowedException.class,
                    () -> memberManagementService.kickMember(ROOM_ID, OWNER_ID, OWNER_ID));
        }

        @Test
        void memberCannotKick_throwsPermissionDenied() {
            stubGeneralRoom();
            RoomMemberEntity normalMember = member(MEMBER_ID, "member", RoomRole.MEMBER);
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, MEMBER_ID))
                    .thenReturn(Optional.of(normalMember));

            assertThrows(PermissionDeniedException.class,
                    () -> memberManagementService.kickMember(ROOM_ID, MEMBER_ID, TARGET_ID));
        }

        @Test
        void kickInDmRoom_throwsRoomTypeNotSupported() {
            stubDmRoom();

            assertThrows(RoomTypeNotSupportedException.class,
                    () -> memberManagementService.kickMember(ROOM_ID, OWNER_ID, TARGET_ID));
        }
    }

    // ── muteMember ──────────────────────────────────────────────

    @Nested
    class MuteMemberTests {

        @Test
        void muteMember_happyPath_setsMutedUntil() {
            stubGeneralRoom();
            RoomMemberEntity owner = member(OWNER_ID, "owner", RoomRole.OWNER);
            RoomMemberEntity target = member(TARGET_ID, "target", RoomRole.MEMBER);
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, OWNER_ID))
                    .thenReturn(Optional.of(owner));
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, TARGET_ID))
                    .thenReturn(Optional.of(target));
            when(roomMemberRepository.findByRoomId(ROOM_ID))
                    .thenReturn(List.of(owner, target));

            LocalDateTime beforeMute = LocalDateTime.now();
            MuteResult result = memberManagementService.muteMember(ROOM_ID, OWNER_ID, TARGET_ID, 30);

            assertNotNull(result.mutedUntil());
            // mutedUntil should be approximately now + 30 minutes
            assertTrue(result.mutedUntil().isAfter(beforeMute.plusMinutes(29)));
            assertTrue(result.mutedUntil().isBefore(beforeMute.plusMinutes(31)));

            ArgumentCaptor<RoomMemberEntity> captor = ArgumentCaptor.forClass(RoomMemberEntity.class);
            verify(roomMemberRepository).save(captor.capture());
            assertEquals(result.mutedUntil(), captor.getValue().getMutedUntil());

            verify(messagingTemplate).convertAndSendToUser(
                    eq(TARGET_ID), eq("/queue/muted"), any(Map.class));
            verify(messagingTemplate).convertAndSend(
                    eq("/topic/chat/" + ROOM_ID + "/members"), any(Map.class));
        }

        @Test
        void muteMember_invalidMinutes_throwsIllegalArgument() {
            assertThrows(IllegalArgumentException.class,
                    () -> memberManagementService.muteMember(ROOM_ID, OWNER_ID, TARGET_ID, 10));
        }

        @Test
        void muteMember_fiveMinutesAllowed() {
            stubGeneralRoom();
            RoomMemberEntity owner = member(OWNER_ID, "owner", RoomRole.OWNER);
            RoomMemberEntity target = member(TARGET_ID, "target", RoomRole.MEMBER);
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, OWNER_ID))
                    .thenReturn(Optional.of(owner));
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, TARGET_ID))
                    .thenReturn(Optional.of(target));
            when(roomMemberRepository.findByRoomId(ROOM_ID))
                    .thenReturn(List.of(owner, target));

            MuteResult result = memberManagementService.muteMember(ROOM_ID, OWNER_ID, TARGET_ID, 5);

            assertNotNull(result.mutedUntil());
        }

        @Test
        void muteMember_sixtyMinutesAllowed() {
            stubGeneralRoom();
            RoomMemberEntity owner = member(OWNER_ID, "owner", RoomRole.OWNER);
            RoomMemberEntity target = member(TARGET_ID, "target", RoomRole.MEMBER);
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, OWNER_ID))
                    .thenReturn(Optional.of(owner));
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, TARGET_ID))
                    .thenReturn(Optional.of(target));
            when(roomMemberRepository.findByRoomId(ROOM_ID))
                    .thenReturn(List.of(owner, target));

            MuteResult result = memberManagementService.muteMember(ROOM_ID, OWNER_ID, TARGET_ID, 60);

            assertNotNull(result.mutedUntil());
        }
    }

    // ── unmuteMember ────────────────────────────────────────────

    @Nested
    class UnmuteMemberTests {

        @Test
        void unmuteMember_clearsMutedUntil() {
            stubGeneralRoom();
            RoomMemberEntity owner = member(OWNER_ID, "owner", RoomRole.OWNER);
            RoomMemberEntity target = member(TARGET_ID, "target", RoomRole.MEMBER);
            target.setMutedUntil(LocalDateTime.now().plusMinutes(30));
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, OWNER_ID))
                    .thenReturn(Optional.of(owner));
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, TARGET_ID))
                    .thenReturn(Optional.of(target));
            when(roomMemberRepository.findByRoomId(ROOM_ID))
                    .thenReturn(List.of(owner, target));

            memberManagementService.unmuteMember(ROOM_ID, OWNER_ID, TARGET_ID);

            ArgumentCaptor<RoomMemberEntity> captor = ArgumentCaptor.forClass(RoomMemberEntity.class);
            verify(roomMemberRepository).save(captor.capture());
            assertNull(captor.getValue().getMutedUntil());

            verify(messagingTemplate).convertAndSend(
                    eq("/topic/chat/" + ROOM_ID + "/members"), any(Map.class));
        }
    }

    // ── changeRole ──────────────────────────────────────────────

    @Nested
    class ChangeRoleTests {

        @Test
        void changeRole_ownerPromotesToMod_success() {
            stubGeneralRoom();
            RoomMemberEntity owner = member(OWNER_ID, "owner", RoomRole.OWNER);
            RoomMemberEntity target = member(TARGET_ID, "target", RoomRole.MEMBER);
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, OWNER_ID))
                    .thenReturn(Optional.of(owner));
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, TARGET_ID))
                    .thenReturn(Optional.of(target));
            when(roomMemberRepository.findByRoomId(ROOM_ID))
                    .thenReturn(List.of(owner, target));

            memberManagementService.changeRole(ROOM_ID, OWNER_ID, TARGET_ID, RoomRole.MODERATOR);

            ArgumentCaptor<RoomMemberEntity> captor = ArgumentCaptor.forClass(RoomMemberEntity.class);
            verify(roomMemberRepository).save(captor.capture());
            assertEquals(RoomRole.MODERATOR, captor.getValue().getRole());
        }

        @Test
        void changeRole_ownerDemotesModToMember_success() {
            stubGeneralRoom();
            RoomMemberEntity owner = member(OWNER_ID, "owner", RoomRole.OWNER);
            RoomMemberEntity target = member(TARGET_ID, "target", RoomRole.MODERATOR);
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, OWNER_ID))
                    .thenReturn(Optional.of(owner));
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, TARGET_ID))
                    .thenReturn(Optional.of(target));
            when(roomMemberRepository.findByRoomId(ROOM_ID))
                    .thenReturn(List.of(owner, target));

            memberManagementService.changeRole(ROOM_ID, OWNER_ID, TARGET_ID, RoomRole.MEMBER);

            ArgumentCaptor<RoomMemberEntity> captor = ArgumentCaptor.forClass(RoomMemberEntity.class);
            verify(roomMemberRepository).save(captor.capture());
            assertEquals(RoomRole.MEMBER, captor.getValue().getRole());
        }

        @Test
        void changeRole_modCannotChangeRoles_throwsPermissionDenied() {
            stubGeneralRoom();
            RoomMemberEntity mod = member(MOD_ID, "mod", RoomRole.MODERATOR);
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, MOD_ID))
                    .thenReturn(Optional.of(mod));

            assertThrows(PermissionDeniedException.class,
                    () -> memberManagementService.changeRole(ROOM_ID, MOD_ID, TARGET_ID, RoomRole.MODERATOR));
        }

        @Test
        void changeRole_cannotPromoteToOwner_throwsIllegalArgument() {
            stubGeneralRoom();
            RoomMemberEntity owner = member(OWNER_ID, "owner", RoomRole.OWNER);
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, OWNER_ID))
                    .thenReturn(Optional.of(owner));

            assertThrows(IllegalArgumentException.class,
                    () -> memberManagementService.changeRole(ROOM_ID, OWNER_ID, TARGET_ID, RoomRole.OWNER));
        }

        @Test
        void changeRole_ownerCannotSelfChange_throwsSelfTarget() {
            stubGeneralRoom();
            RoomMemberEntity owner = member(OWNER_ID, "owner", RoomRole.OWNER);
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, OWNER_ID))
                    .thenReturn(Optional.of(owner));

            assertThrows(SelfTargetNotAllowedException.class,
                    () -> memberManagementService.changeRole(ROOM_ID, OWNER_ID, OWNER_ID, RoomRole.MEMBER));
        }
    }

    // ── transferOwnership ───────────────────────────────────────

    @Nested
    class TransferOwnershipTests {

        @Test
        void transferOwnership_success_swapsRoles() {
            stubGeneralRoom();
            RoomMemberEntity currentOwner = member(OWNER_ID, "owner", RoomRole.OWNER);
            RoomMemberEntity newOwner = member(TARGET_ID, "target", RoomRole.MEMBER);
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, OWNER_ID))
                    .thenReturn(Optional.of(currentOwner));
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, TARGET_ID))
                    .thenReturn(Optional.of(newOwner));
            when(roomMemberRepository.findByRoomId(ROOM_ID))
                    .thenReturn(List.of(currentOwner, newOwner));

            memberManagementService.transferOwnership(ROOM_ID, OWNER_ID, TARGET_ID);

            assertEquals(RoomRole.MODERATOR, currentOwner.getRole());
            assertEquals(RoomRole.OWNER, newOwner.getRole());
            verify(roomMemberRepository, times(2)).save(any(RoomMemberEntity.class));
            verify(messagingTemplate).convertAndSend(
                    eq("/topic/chat/" + ROOM_ID + "/members"), any(Map.class));
        }

        @Test
        void transferOwnership_nonOwnerCaller_throwsPermissionDenied() {
            stubGeneralRoom();
            RoomMemberEntity mod = member(MOD_ID, "mod", RoomRole.MODERATOR);
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, MOD_ID))
                    .thenReturn(Optional.of(mod));

            assertThrows(PermissionDeniedException.class,
                    () -> memberManagementService.transferOwnership(ROOM_ID, MOD_ID, TARGET_ID));
        }

        @Test
        void transferOwnership_selfTarget_throwsSelfTarget() {
            stubGeneralRoom();
            RoomMemberEntity owner = member(OWNER_ID, "owner", RoomRole.OWNER);
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, OWNER_ID))
                    .thenReturn(Optional.of(owner));

            assertThrows(SelfTargetNotAllowedException.class,
                    () -> memberManagementService.transferOwnership(ROOM_ID, OWNER_ID, OWNER_ID));
        }
    }
}
