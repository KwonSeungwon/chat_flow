package com.gossiplog.chat.service.room;

import com.gossiplog.chat.entity.ChatRoom;
import com.gossiplog.chat.entity.RoomType;
import com.gossiplog.chat.repository.ChatRoomRepository;
import com.gossiplog.chat.repository.RoomMemberRepository;
import com.gossiplog.chat.service.room.RoomAccessPolicy.Denial;
import com.gossiplog.chat.service.room.RoomAccessPolicy.GrantPath;
import com.gossiplog.chat.service.room.RoomAccessPolicy.JoinDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoomAccessPolicyTest {

    @Mock private ChatRoomRepository chatRoomRepository;
    @Mock private RoomMemberRepository roomMemberRepository;
    @Mock private RoomBanService roomBanService;
    @Mock private ParticipantService participantService;

    private RoomAccessPolicy policy;

    private static final String ROOM_ID = "room-1";
    private static final String USER_ID = "user-1";

    @BeforeEach
    void setUp() {
        policy = new RoomAccessPolicy(chatRoomRepository, roomMemberRepository, roomBanService, participantService);
    }

    private ChatRoom room(RoomType type, boolean isPrivate, String password) {
        return ChatRoom.builder()
                .id(ROOM_ID)
                .name("room")
                .roomType(type)
                .isPrivate(isPrivate)
                .password(password)
                .build();
    }

    private void givenRoom(ChatRoom room) {
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
    }

    private void givenMember(boolean member) {
        when(roomMemberRepository.existsByRoomIdAndUserId(ROOM_ID, USER_ID)).thenReturn(member);
    }

    // ── canAutoJoin ──────────────────────────────────────────────

    @Test
    void autoJoin_publicRoom_allowed() {
        givenRoom(room(RoomType.GENERAL, false, null));
        givenMember(false);

        JoinDecision d = policy.canAutoJoin(ROOM_ID, USER_ID);

        assertTrue(d.allowed());
        assertNull(d.reason());
    }

    @Test
    void autoJoin_dmRoom_nonParticipant_deniedDmNotParticipant() {
        givenRoom(room(RoomType.DIRECT, false, null));
        givenMember(false);

        JoinDecision d = policy.canAutoJoin(ROOM_ID, USER_ID);

        assertFalse(d.allowed());
        assertEquals(Denial.DM_NOT_PARTICIPANT, d.reason());
    }

    @Test
    void autoJoin_dmRoom_participant_allowed() {
        givenRoom(room(RoomType.DIRECT, false, null));
        givenMember(true);

        assertTrue(policy.canAutoJoin(ROOM_ID, USER_ID).allowed());
    }

    @Test
    void autoJoin_passwordRoom_deniedPasswordRequired() {
        givenRoom(room(RoomType.GENERAL, false, "$2a$10$hash"));
        givenMember(false);

        assertEquals(Denial.PASSWORD_REQUIRED, policy.canAutoJoin(ROOM_ID, USER_ID).reason());
    }

    @Test
    void autoJoin_privateRoom_deniedPrivateRequiresInvite() {
        givenRoom(room(RoomType.GENERAL, true, null));
        givenMember(false);

        assertEquals(Denial.PRIVATE_REQUIRES_INVITE, policy.canAutoJoin(ROOM_ID, USER_ID).reason());
    }

    @Test
    void autoJoin_privateRoom_existingMember_allowed() {
        givenRoom(room(RoomType.GENERAL, true, "$2a$10$hash"));
        givenMember(true);

        assertTrue(policy.canAutoJoin(ROOM_ID, USER_ID).allowed());
    }

    @Test
    void autoJoin_banned_deniedBanned() {
        givenRoom(room(RoomType.GENERAL, false, null));
        when(roomBanService.isBanned(ROOM_ID, USER_ID)).thenReturn(true);

        assertEquals(Denial.BANNED, policy.canAutoJoin(ROOM_ID, USER_ID).reason());
    }

    @Test
    void autoJoin_missingRoom_deniedRoomNotFound() {
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Optional.empty());

        assertEquals(Denial.ROOM_NOT_FOUND, policy.canAutoJoin(ROOM_ID, USER_ID).reason());
    }

    // ── canGrant ─────────────────────────────────────────────────

    @Test
    void grant_invite_banned_deniedBanned() {
        givenRoom(room(RoomType.GENERAL, true, null));
        when(roomBanService.isBanned(ROOM_ID, USER_ID)).thenReturn(true);

        assertEquals(Denial.BANNED, policy.canGrant(ROOM_ID, USER_ID, GrantPath.INVITE).reason());
    }

    @Test
    void grant_invite_privateRoom_allowed() {
        givenRoom(room(RoomType.GENERAL, true, "$2a$10$hash"));
        givenMember(false);
        when(participantService.isRoomFull(ROOM_ID)).thenReturn(false);

        assertTrue(policy.canGrant(ROOM_ID, USER_ID, GrantPath.INVITE).allowed());
    }

    @Test
    void grant_invite_fullRoom_deniedRoomFull() {
        givenRoom(room(RoomType.GENERAL, false, null));
        givenMember(false);
        when(participantService.isRoomFull(ROOM_ID)).thenReturn(true);

        assertEquals(Denial.ROOM_FULL, policy.canGrant(ROOM_ID, USER_ID, GrantPath.INVITE).reason());
    }

    @Test
    void grant_invite_dmNonParticipant_denied() {
        givenRoom(room(RoomType.DIRECT, false, null));
        givenMember(false);

        assertEquals(Denial.DM_NOT_PARTICIPANT,
                policy.canGrant(ROOM_ID, USER_ID, GrantPath.INVITE).reason());
    }

    @Test
    void grant_password_onRoomWithoutPassword_denied() {
        givenRoom(room(RoomType.GENERAL, false, null));

        JoinDecision d = policy.canGrant(ROOM_ID, USER_ID, GrantPath.PASSWORD);

        assertFalse(d.allowed());
        assertEquals(Denial.PASSWORD_REQUIRED, d.reason());
    }

    @Test
    void grant_password_onPasswordRoom_allowed() {
        givenRoom(room(RoomType.GENERAL, false, "$2a$10$hash"));

        assertTrue(policy.canGrant(ROOM_ID, USER_ID, GrantPath.PASSWORD).allowed());
    }

    @Test
    void grant_password_onDm_denied() {
        givenRoom(room(RoomType.DIRECT, false, "$2a$10$hash"));

        assertEquals(Denial.DM_NOT_PARTICIPANT,
                policy.canGrant(ROOM_ID, USER_ID, GrantPath.PASSWORD).reason());
    }

    @Test
    void grant_password_onPrivateRoom_deniedPrivateRequiresInvite() {
        givenRoom(room(RoomType.GENERAL, true, "$2a$10$hash"));

        assertEquals(Denial.PRIVATE_REQUIRES_INVITE,
                policy.canGrant(ROOM_ID, USER_ID, GrantPath.PASSWORD).reason());
    }

    @Test
    void grant_dmCreate_onDm_allowed_onGeneral_denied() {
        givenRoom(room(RoomType.DIRECT, false, null));
        assertTrue(policy.canGrant(ROOM_ID, USER_ID, GrantPath.DM_CREATE).allowed());

        givenRoom(room(RoomType.GENERAL, false, null));
        assertEquals(Denial.DM_NOT_PARTICIPANT,
                policy.canGrant(ROOM_ID, USER_ID, GrantPath.DM_CREATE).reason());
    }

    @Test
    void grant_dmCreate_deniedForAThirdUser_whenTheDmAlreadyHasTwoMembers() {
        givenRoom(room(RoomType.DIRECT, false, null));
        when(roomMemberRepository.existsByRoomIdAndUserId(ROOM_ID, USER_ID)).thenReturn(false);
        when(roomMemberRepository.countByRoomId(ROOM_ID)).thenReturn(2L);

        assertEquals(Denial.DM_NOT_PARTICIPANT,
                policy.canGrant(ROOM_ID, USER_ID, GrantPath.DM_CREATE).reason());
    }

    @Test
    void grant_dmCreate_allowedForAnExistingParticipant() {
        givenRoom(room(RoomType.DIRECT, false, null));
        when(roomMemberRepository.existsByRoomIdAndUserId(ROOM_ID, USER_ID)).thenReturn(true);

        assertTrue(policy.canGrant(ROOM_ID, USER_ID, GrantPath.DM_CREATE).allowed());
    }

    // ── isVisible ────────────────────────────────────────────────

    @Test
    void isVisible_dmOfOthers_false() {
        givenMember(false);
        assertFalse(policy.isVisible(room(RoomType.DIRECT, false, null), USER_ID));
    }

    @Test
    void isVisible_dmOfMine_true() {
        givenMember(true);
        assertTrue(policy.isVisible(room(RoomType.DIRECT, false, null), USER_ID));
    }

    @Test
    void isVisible_privateNonMember_false() {
        givenMember(false);
        assertFalse(policy.isVisible(room(RoomType.GENERAL, true, null), USER_ID));
    }

    @Test
    void isVisible_passwordRoom_true() {
        assertTrue(policy.isVisible(room(RoomType.GENERAL, false, "$2a$10$hash"), USER_ID));
        verifyNoInteractions(roomMemberRepository);
    }

    @Test
    void isVisible_anonymous_onlyPublicNonDm() {
        assertTrue(policy.isVisible(room(RoomType.GENERAL, false, null), null));
        assertFalse(policy.isVisible(room(RoomType.DIRECT, false, null), null));
        assertFalse(policy.isVisible(room(RoomType.GENERAL, true, null), null));
    }
}
