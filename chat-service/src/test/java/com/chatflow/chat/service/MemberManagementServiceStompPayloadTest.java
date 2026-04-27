package com.chatflow.chat.service;

import com.chatflow.chat.entity.ChatRoom;
import com.chatflow.chat.entity.RoomMemberEntity;
import com.chatflow.chat.entity.RoomRole;
import com.chatflow.chat.entity.RoomType;
import com.chatflow.chat.repository.ChatRoomRepository;
import com.chatflow.chat.repository.RoomBanRepository;
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
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Verifies STOMP event payloads emitted by MemberManagementService and RoomBanService
 * match the spec section 7 wire format.
 *
 * Existing T2 tests verify happy/error paths. This test focuses specifically on:
 * - Kicked queue payload shape: { roomId, reason, by, byUserId }
 * - Muted queue payload shape: { roomId, mutedUntil, by, byUserId }
 * - Banned queue payload shape: { roomId, reason: "BANNED", by, byUserId }
 * - MemberListBroadcaster.broadcast() invocation with correct roomId
 *
 * Note: MemberListBroadcasterTest already verifies the /topic/chat/{roomId}/members
 * payload shape (MEMBER_LIST_UPDATED + members array + timestamp).
 */
@ExtendWith(MockitoExtension.class)
class MemberManagementServiceStompPayloadTest {

    @Mock private RoomMemberRepository roomMemberRepository;
    @Mock private RoomBanRepository roomBanRepository;
    @Mock private ChatRoomRepository chatRoomRepository;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private MemberListBroadcaster memberListBroadcaster;

    private RoomPermissionService roomPermissionService;
    private MemberManagementService memberManagementService;
    private RoomBanService roomBanService;

    private static final String ROOM_ID = "room-1";
    private static final String ACTOR_ID = "actor-owner";
    private static final String TARGET_ID = "target-member";

    @BeforeEach
    void setUp() {
        roomPermissionService = new RoomPermissionService(roomMemberRepository, chatRoomRepository);
        memberManagementService = new MemberManagementService(
                roomMemberRepository, roomPermissionService, messagingTemplate, memberListBroadcaster);
        roomBanService = new RoomBanService(
                roomBanRepository, roomMemberRepository, roomPermissionService,
                messagingTemplate, memberListBroadcaster);
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

    // ── kickMember — spec section 7.2 /user/queue/kicked format ──

    @Nested
    class KickStompPayload {

        @Test
        @SuppressWarnings("unchecked")
        void kickMember_payloadHasAllRequiredFields() {
            stubGeneralRoom();
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, ACTOR_ID))
                    .thenReturn(Optional.of(member(ACTOR_ID, "OwnerName", RoomRole.OWNER)));
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, TARGET_ID))
                    .thenReturn(Optional.of(member(TARGET_ID, "TargetName", RoomRole.MEMBER)));

            memberManagementService.kickMember(ROOM_ID, ACTOR_ID, TARGET_ID);

            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(messagingTemplate).convertAndSendToUser(
                    eq(TARGET_ID), eq("/queue/kicked"), captor.capture());

            Map<String, Object> payload = captor.getValue();
            // Spec section 7.2: { roomId, reason: "KICKED"|"BANNED", by: "username" }
            assertEquals(ROOM_ID, payload.get("roomId"), "roomId must be present");
            assertEquals("KICKED", payload.get("reason"), "reason must be 'KICKED'");
            assertEquals("OwnerName", payload.get("by"), "by must be actor's username");
            assertEquals(ACTOR_ID, payload.get("byUserId"), "byUserId must be actor's userId");
            assertEquals(4, payload.size(), "payload should have exactly 4 fields");
        }

        @Test
        void kickMember_broadcastsToCorrectRoom() {
            stubGeneralRoom();
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, ACTOR_ID))
                    .thenReturn(Optional.of(member(ACTOR_ID, "OwnerName", RoomRole.OWNER)));
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, TARGET_ID))
                    .thenReturn(Optional.of(member(TARGET_ID, "TargetName", RoomRole.MEMBER)));

            memberManagementService.kickMember(ROOM_ID, ACTOR_ID, TARGET_ID);

            verify(memberListBroadcaster).broadcast(ROOM_ID);
        }
    }

    // ── muteMember — spec section 7.2 /user/queue/muted format ──

    @Nested
    class MuteStompPayload {

        @Test
        @SuppressWarnings("unchecked")
        void muteMember_payloadHasAllRequiredFields() {
            stubGeneralRoom();
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, ACTOR_ID))
                    .thenReturn(Optional.of(member(ACTOR_ID, "OwnerName", RoomRole.OWNER)));
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, TARGET_ID))
                    .thenReturn(Optional.of(member(TARGET_ID, "TargetName", RoomRole.MEMBER)));

            MuteResult result = memberManagementService.muteMember(ROOM_ID, ACTOR_ID, TARGET_ID, 30);

            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(messagingTemplate).convertAndSendToUser(
                    eq(TARGET_ID), eq("/queue/muted"), captor.capture());

            Map<String, Object> payload = captor.getValue();
            // Spec section 7.2: { roomId, mutedUntil: "...", by: "username" }
            assertEquals(ROOM_ID, payload.get("roomId"), "roomId must be present");
            assertNotNull(payload.get("mutedUntil"), "mutedUntil must be present");
            assertEquals(result.mutedUntil().toString(), payload.get("mutedUntil"),
                    "mutedUntil must match the returned value");
            assertEquals("OwnerName", payload.get("by"), "by must be actor's username");
            assertEquals(ACTOR_ID, payload.get("byUserId"), "byUserId must be actor's userId");
            assertEquals(4, payload.size(), "payload should have exactly 4 fields");
        }

        @Test
        void muteMember_broadcastsToCorrectRoom() {
            stubGeneralRoom();
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, ACTOR_ID))
                    .thenReturn(Optional.of(member(ACTOR_ID, "OwnerName", RoomRole.OWNER)));
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, TARGET_ID))
                    .thenReturn(Optional.of(member(TARGET_ID, "TargetName", RoomRole.MEMBER)));

            memberManagementService.muteMember(ROOM_ID, ACTOR_ID, TARGET_ID, 5);

            verify(memberListBroadcaster).broadcast(ROOM_ID);
        }
    }

    // ── banUser — spec section 7.2 /user/queue/kicked with reason "BANNED" ──

    @Nested
    class BanStompPayload {

        @Test
        @SuppressWarnings("unchecked")
        void banUser_payloadHasAllRequiredFields() {
            stubGeneralRoom();
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, ACTOR_ID))
                    .thenReturn(Optional.of(member(ACTOR_ID, "OwnerName", RoomRole.OWNER)));
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, TARGET_ID))
                    .thenReturn(Optional.of(member(TARGET_ID, "TargetName", RoomRole.MEMBER)));

            roomBanService.banUser(ROOM_ID, ACTOR_ID, TARGET_ID, "spam");

            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(messagingTemplate).convertAndSendToUser(
                    eq(TARGET_ID), eq("/queue/kicked"), captor.capture());

            Map<String, Object> payload = captor.getValue();
            // Spec section 7.2: { roomId, reason: "BANNED", by: "username" }
            assertEquals(ROOM_ID, payload.get("roomId"), "roomId must be present");
            assertEquals("BANNED", payload.get("reason"), "reason must be 'BANNED' for ban");
            assertEquals("OwnerName", payload.get("by"), "by must be actor's username");
            assertEquals(ACTOR_ID, payload.get("byUserId"), "byUserId must be actor's userId");
            assertEquals(4, payload.size(), "payload should have exactly 4 fields");
        }

        @Test
        void banUser_broadcastsToCorrectRoom() {
            stubGeneralRoom();
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, ACTOR_ID))
                    .thenReturn(Optional.of(member(ACTOR_ID, "OwnerName", RoomRole.OWNER)));
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, TARGET_ID))
                    .thenReturn(Optional.of(member(TARGET_ID, "TargetName", RoomRole.MEMBER)));

            roomBanService.banUser(ROOM_ID, ACTOR_ID, TARGET_ID, "spam");

            verify(memberListBroadcaster).broadcast(ROOM_ID);
        }
    }
}
