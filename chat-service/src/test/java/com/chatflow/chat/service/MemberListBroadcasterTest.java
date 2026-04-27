package com.chatflow.chat.service;

import com.chatflow.chat.entity.RoomMemberEntity;
import com.chatflow.chat.entity.RoomRole;
import com.chatflow.chat.repository.RoomMemberRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemberListBroadcasterTest {

    @Mock
    private RoomMemberRepository roomMemberRepository;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private MemberListBroadcaster memberListBroadcaster;

    private static final String ROOM_ID = "room-1";

    @Test
    @SuppressWarnings("unchecked")
    void broadcast_sendsCorrectPayloadShape() {
        LocalDateTime mutedUntil = LocalDateTime.of(2026, 4, 27, 12, 0, 0);
        RoomMemberEntity owner = RoomMemberEntity.builder()
                .roomId(ROOM_ID).userId("owner-1").username("owner")
                .role(RoomRole.OWNER).joinedAt(LocalDateTime.now()).build();
        RoomMemberEntity member = RoomMemberEntity.builder()
                .roomId(ROOM_ID).userId("member-1").username("member")
                .role(RoomRole.MEMBER).mutedUntil(mutedUntil).joinedAt(LocalDateTime.now()).build();

        when(roomMemberRepository.findByRoomId(ROOM_ID)).thenReturn(List.of(owner, member));

        memberListBroadcaster.broadcast(ROOM_ID);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(messagingTemplate).convertAndSend(
                eq("/topic/chat/" + ROOM_ID + "/members"), captor.capture());

        Map<String, Object> payload = captor.getValue();
        assertEquals("MEMBER_LIST_UPDATED", payload.get("type"));
        assertNotNull(payload.get("timestamp"));

        List<Map<String, Object>> members = (List<Map<String, Object>>) payload.get("members");
        assertEquals(2, members.size());

        // First member: owner (no mute)
        Map<String, Object> ownerMap = members.get(0);
        assertEquals("owner-1", ownerMap.get("userId"));
        assertEquals("owner", ownerMap.get("username"));
        assertEquals("OWNER", ownerMap.get("role"));
        assertNull(ownerMap.get("mutedUntil"));

        // Second member: muted member
        Map<String, Object> memberMap = members.get(1);
        assertEquals("member-1", memberMap.get("userId"));
        assertEquals("member", memberMap.get("username"));
        assertEquals("MEMBER", memberMap.get("role"));
        assertEquals(mutedUntil.toString(), memberMap.get("mutedUntil"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void broadcast_emptyRoom_sendsEmptyMemberList() {
        when(roomMemberRepository.findByRoomId(ROOM_ID)).thenReturn(List.of());

        memberListBroadcaster.broadcast(ROOM_ID);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(messagingTemplate).convertAndSend(
                eq("/topic/chat/" + ROOM_ID + "/members"), captor.capture());

        Map<String, Object> payload = captor.getValue();
        assertEquals("MEMBER_LIST_UPDATED", payload.get("type"));
        List<Map<String, Object>> members = (List<Map<String, Object>>) payload.get("members");
        assertTrue(members.isEmpty());
    }
}
